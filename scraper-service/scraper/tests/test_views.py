"""Tests for the two internal HTTP endpoints. Mongo is backed by
mongomock (via a monkeypatched `mongo.get_store`) and the ESPN scraper is
mocked -- no real network or real Mongo involved."""

from datetime import datetime, timedelta, timezone
from unittest import mock

import mongomock
from django.test import TestCase

from scraper.exceptions import ScraperError
from scraper.mongo import SnapshotStore


def _fresh_store() -> SnapshotStore:
    return SnapshotStore(client=mongomock.MongoClient())


class LatestViewTests(TestCase):
    def setUp(self):
        self.store = _fresh_store()
        patcher = mock.patch("scraper.mongo.get_store", return_value=self.store)
        patcher.start()
        self.addCleanup(patcher.stop)

    def test_404_when_never_scraped(self):
        response = self.client.get("/internal/espn/premier-league/standings/latest")

        self.assertEqual(response.status_code, 404)
        body = response.json()
        self.assertEqual(body["error"], "not_found")
        self.assertEqual(body["league"], "premier-league")
        self.assertEqual(body["data_type"], "standings")

    def test_400_for_unsupported_league(self):
        response = self.client.get("/internal/espn/la-liga/standings/latest")
        self.assertEqual(response.status_code, 400)
        self.assertEqual(response.json()["error"], "unsupported")

    def test_400_for_unsupported_data_type(self):
        response = self.client.get(
            "/internal/espn/premier-league/top-scorers/latest"
        )
        self.assertEqual(response.status_code, 400)
        self.assertEqual(response.json()["error"], "unsupported")

    def test_200_fresh_is_not_stale(self):
        now = datetime.now(timezone.utc)
        self.store.upsert(
            "premier-league", "standings", "2025-26", now, {"table": []}
        )

        response = self.client.get(
            "/internal/espn/premier-league/standings/latest",
            {"max_age_seconds": 300},
        )

        self.assertEqual(response.status_code, 200)
        body = response.json()
        self.assertEqual(body["league"], "premier-league")
        self.assertEqual(body["data_type"], "standings")
        self.assertEqual(body["season"], "2025-26")
        self.assertFalse(body["stale"])
        self.assertLessEqual(body["age_seconds"], 2)
        self.assertEqual(body["data"], {"table": []})

    def test_200_old_snapshot_is_stale(self):
        old = datetime.now(timezone.utc) - timedelta(seconds=600)
        self.store.upsert(
            "premier-league", "standings", "2025-26", old, {"table": []}
        )

        response = self.client.get(
            "/internal/espn/premier-league/standings/latest",
            {"max_age_seconds": 300},
        )

        self.assertEqual(response.status_code, 200)
        body = response.json()
        self.assertTrue(body["stale"])
        self.assertGreaterEqual(body["age_seconds"], 600)

    def test_default_max_age_used_when_omitted(self):
        # 400s old: stale under the default 300s staleness setting used in
        # tests (see settings via SCRAPER_STALENESS_SECONDS env default).
        old = datetime.now(timezone.utc) - timedelta(seconds=400)
        self.store.upsert(
            "premier-league", "standings", "2025-26", old, {"table": []}
        )

        response = self.client.get("/internal/espn/premier-league/standings/latest")

        self.assertEqual(response.status_code, 200)
        self.assertTrue(response.json()["stale"])


class RefreshViewTests(TestCase):
    def setUp(self):
        self.store = _fresh_store()
        patcher = mock.patch("scraper.mongo.get_store", return_value=self.store)
        patcher.start()
        self.addCleanup(patcher.stop)

    def _post(self, league="premier-league", data_type="standings"):
        return self.client.post(
            f"/internal/espn/{league}/{data_type}/refresh",
            data="{}",
            content_type="application/json",
        )

    def test_400_for_unsupported(self):
        response = self._post(league="serie-a")
        self.assertEqual(response.status_code, 400)
        self.assertEqual(response.json()["error"], "unsupported")

    @mock.patch("scraper.views.espn.scrape")
    def test_200_success_upserts_and_returns_fresh_doc(self, mock_scrape):
        mock_scrape.return_value = ({"table": [{"rank": 1}]}, "2025-26")

        response = self._post()

        self.assertEqual(response.status_code, 200)
        body = response.json()
        self.assertEqual(body["data"], {"table": [{"rank": 1}]})
        self.assertEqual(body["season"], "2025-26")
        self.assertFalse(body["stale"])
        self.assertEqual(body["age_seconds"], 0)

        # And it's actually in the store now, for a subsequent /latest call.
        stored = self.store.get("premier-league", "standings")
        self.assertIsNotNone(stored)
        self.assertEqual(stored["data"], {"table": [{"rank": 1}]})

    @mock.patch("scraper.views.espn.scrape")
    def test_502_on_scrape_failure(self, mock_scrape):
        mock_scrape.side_effect = ScraperError("espn is down")

        response = self._post()

        self.assertEqual(response.status_code, 502)
        body = response.json()
        self.assertEqual(body["error"], "scrape_failed")
        self.assertEqual(body["league"], "premier-league")
        self.assertEqual(body["data_type"], "standings")
        self.assertIn("espn is down", body["detail"])

        # A failed refresh must not leave a bad doc behind.
        self.assertIsNone(self.store.get("premier-league", "standings"))

    def test_get_not_allowed_on_refresh(self):
        response = self.client.get("/internal/espn/premier-league/standings/refresh")
        self.assertEqual(response.status_code, 405)

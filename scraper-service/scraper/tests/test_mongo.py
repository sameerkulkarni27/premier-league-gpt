"""Tests for scraper/mongo.py's upsert + one-doc-per-league/data_type
behavior, using mongomock instead of a real MongoDB instance."""

from datetime import datetime, timedelta, timezone

import mongomock
from django.test import SimpleTestCase

from scraper.mongo import SnapshotStore


def _store() -> SnapshotStore:
    return SnapshotStore(client=mongomock.MongoClient())


class SnapshotStoreTests(SimpleTestCase):
    def test_get_returns_none_when_never_scraped(self):
        store = _store()
        self.assertIsNone(store.get("premier-league", "standings"))

    def test_upsert_then_get_round_trips(self):
        store = _store()
        scraped_at = datetime(2026, 9, 13, 18, 30, tzinfo=timezone.utc)

        doc = store.upsert(
            "premier-league", "standings", "2025-26", scraped_at, {"table": []}
        )

        self.assertEqual(doc["league"], "premier-league")
        self.assertEqual(doc["data_type"], "standings")
        self.assertEqual(doc["season"], "2025-26")
        self.assertEqual(doc["data"], {"table": []})

        fetched = store.get("premier-league", "standings")
        self.assertEqual(fetched["data"], {"table": []})

    def test_upsert_is_one_document_per_league_and_data_type(self):
        """Re-scraping the same league+data_type must overwrite in place,
        not create a second document -- this is the whole point of the
        unique compound index in PLAN.md."""
        store = _store()
        t1 = datetime(2026, 9, 13, 12, 0, tzinfo=timezone.utc)
        t2 = t1 + timedelta(minutes=10)

        store.upsert("premier-league", "standings", "2025-26", t1, {"table": [1]})
        store.upsert("premier-league", "standings", "2025-26", t2, {"table": [1, 2]})

        # Only one doc exists for this (league, data_type) pair.
        count = store._collection.count_documents(
            {"league": "premier-league", "data_type": "standings"}
        )
        self.assertEqual(count, 1)

        fetched = store.get("premier-league", "standings")
        self.assertEqual(fetched["data"], {"table": [1, 2]})

    def test_different_data_types_are_independent_documents(self):
        store = _store()
        now = datetime.now(timezone.utc)
        store.upsert("premier-league", "standings", "2025-26", now, {"table": []})
        store.upsert("premier-league", "fixtures", "2025-26", now, {"fixtures": []})

        self.assertEqual(
            store._collection.count_documents({"league": "premier-league"}), 2
        )
        self.assertIsNotNone(store.get("premier-league", "fixtures"))
        self.assertIsNotNone(store.get("premier-league", "standings"))

    def test_unique_index_created(self):
        store = _store()
        index_info = store._collection.index_information()
        keys = [tuple(spec["key"]) for spec in index_info.values()]
        self.assertIn((("league", 1), ("data_type", 1)), keys)

"""Unit tests for scraper/espn.py. The HTTP layer (requests.get) is always
mocked -- these never hit real ESPN."""

from unittest import mock

from django.test import SimpleTestCase

from scraper import espn
from scraper.exceptions import ScraperError

from .fixtures import SCOREBOARD_PAYLOAD, STANDINGS_PAYLOAD


def _mock_response(json_body, status_code=200):
    response = mock.Mock()
    response.status_code = status_code
    response.json.return_value = json_body
    if status_code >= 400:
        response.raise_for_status.side_effect = Exception("http error")
    else:
        response.raise_for_status.return_value = None
    return response


class ScrapeStandingsTests(SimpleTestCase):
    @mock.patch("scraper.espn.requests.get")
    def test_parses_table_shape(self, mock_get):
        mock_get.return_value = _mock_response(STANDINGS_PAYLOAD)

        data, season = espn.scrape_standings("eng.1")

        self.assertEqual(season, "2026-27")
        self.assertEqual(len(data["table"]), 2)

        arsenal = data["table"][0]
        self.assertEqual(arsenal["rank"], 1)
        self.assertEqual(arsenal["team"], "Arsenal")
        self.assertEqual(arsenal["team_id"], "359")
        self.assertEqual(arsenal["played"], 5)
        self.assertEqual(arsenal["won"], 4)
        self.assertEqual(arsenal["drawn"], 1)
        self.assertEqual(arsenal["lost"], 0)
        self.assertEqual(arsenal["goals_for"], 12)
        self.assertEqual(arsenal["goals_against"], 3)
        self.assertEqual(arsenal["goal_diff"], 9)
        self.assertEqual(arsenal["points"], 13)
        self.assertEqual(arsenal["form"], [])

        # Called ESPN's standings endpoint for the given league code.
        called_url = mock_get.call_args.args[0]
        self.assertIn("eng.1", called_url)
        self.assertIn("standings", called_url)

    @mock.patch("scraper.espn.requests.get")
    def test_unexpected_shape_raises_scraper_error(self, mock_get):
        mock_get.return_value = _mock_response({"nope": True})

        with self.assertRaises(ScraperError):
            espn.scrape_standings("eng.1")

    @mock.patch("scraper.espn.requests.get")
    def test_network_failure_raises_scraper_error(self, mock_get):
        import requests

        mock_get.side_effect = requests.ConnectionError("boom")

        with self.assertRaises(ScraperError):
            espn.scrape_standings("eng.1")


class ScrapeFixturesAndResultsTests(SimpleTestCase):
    @mock.patch("scraper.espn.requests.get")
    def test_fixtures_only_includes_scheduled_events(self, mock_get):
        mock_get.return_value = _mock_response(SCOREBOARD_PAYLOAD)

        data, season = espn.scrape_fixtures("eng.1")

        self.assertEqual(season, "2026-27")
        self.assertEqual(len(data["fixtures"]), 1)
        fixture = data["fixtures"][0]
        self.assertEqual(fixture["event_id"], "401879280")
        self.assertEqual(fixture["home_team"], "Arsenal")
        self.assertEqual(fixture["away_team"], "Chelsea")
        self.assertEqual(fixture["venue"], "Emirates Stadium")
        self.assertEqual(fixture["status"], "scheduled")
        self.assertEqual(fixture["date"], "2026-09-20T14:00:00Z")

    @mock.patch("scraper.espn.requests.get")
    def test_results_only_includes_finished_events(self, mock_get):
        mock_get.return_value = _mock_response(SCOREBOARD_PAYLOAD)

        data, _season = espn.scrape_results("eng.1")

        self.assertEqual(len(data["results"]), 1)
        result = data["results"][0]
        self.assertEqual(result["event_id"], "401879282")
        self.assertEqual(result["home_team"], "Coventry City")
        self.assertEqual(result["away_team"], "Brighton & Hove Albion")
        self.assertEqual(result["home_score"], 0)
        self.assertEqual(result["away_score"], 5)
        self.assertEqual(result["status"], "final")

    @mock.patch("scraper.espn.requests.get")
    def test_cookie_header_sent_when_configured(self, mock_get):
        mock_get.return_value = _mock_response(STANDINGS_PAYLOAD)

        with mock.patch("scraper.espn.settings.ESPN_COOKIE_HEADER", "foo=bar"):
            espn.scrape_standings("eng.1")

        sent_headers = mock_get.call_args.kwargs["headers"]
        self.assertEqual(sent_headers["Cookie"], "foo=bar")

    @mock.patch("scraper.espn.requests.get")
    def test_no_cookie_header_when_unset(self, mock_get):
        mock_get.return_value = _mock_response(STANDINGS_PAYLOAD)

        with mock.patch("scraper.espn.settings.ESPN_COOKIE_HEADER", ""):
            espn.scrape_standings("eng.1")

        sent_headers = mock_get.call_args.kwargs["headers"]
        self.assertNotIn("Cookie", sent_headers)


class DispatcherTests(SimpleTestCase):
    def test_unknown_data_type_raises_value_error(self):
        with self.assertRaises(ValueError):
            espn.scrape("not-a-real-type", "eng.1")

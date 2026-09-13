"""
ESPN scraper for Premier League standings/fixtures/results.

Targets ESPN's public JSON "site API" (the same endpoints ESPN's own
website calls) rather than scraping rendered HTML:

  - standings: https://site.api.espn.com/apis/v2/sports/soccer/<code>/standings
  - fixtures/results: https://site.api.espn.com/apis/site/v2/sports/soccer/<code>/scoreboard?dates=<range>

`<code>` is ESPN's internal league code, e.g. "eng.1" for the Premier
League (see scraper.config.LEAGUE_TO_ESPN_CODE).

Bot detection: espn.com itself sits behind an AWS WAF JS challenge that a
plain HTTP client cannot pass even with a captured cookie (verified during
development -- see scraper-service/README.md "ESPN bot-detection" section).
The site.api.espn.com JSON endpoints used here are NOT behind that
challenge and work with a normal User-Agent; ESPN_COOKIE_HEADER is still
attached when present as a best-effort extra measure, and requests never
fail solely for lacking it.

This module is intentionally self-contained and swappable: everything a
caller needs is `scrape_standings`, `scrape_fixtures`, `scrape_results`,
and the `scrape(data_type, espn_league)` dispatcher.
"""

from __future__ import annotations

from datetime import datetime, timedelta, timezone
from typing import Any

import requests
from django.conf import settings

from .exceptions import ScraperError

ESPN_API_BASE = "https://site.api.espn.com"

# How far ahead/behind "now" we look for fixtures/results. ESPN's scoreboard
# endpoint requires an explicit date (range); there's no "just give me the
# next N fixtures" call, so we window it.
FIXTURES_WINDOW_DAYS = 30
RESULTS_WINDOW_DAYS = 30

_USER_AGENT = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
)

_REQUEST_TIMEOUT_SECONDS = 15


def _headers() -> dict[str, str]:
    headers = {
        "User-Agent": _USER_AGENT,
        "Accept": "application/json",
    }
    cookie = getattr(settings, "ESPN_COOKIE_HEADER", "") or ""
    if cookie:
        headers["Cookie"] = cookie
    return headers


def _get_json(url: str, params: dict[str, Any] | None = None) -> dict[str, Any]:
    try:
        response = requests.get(
            url, headers=_headers(), params=params, timeout=_REQUEST_TIMEOUT_SECONDS
        )
        response.raise_for_status()
    except requests.RequestException as exc:
        raise ScraperError(f"request to {url} failed: {exc}") from exc

    try:
        return response.json()
    except ValueError as exc:
        raise ScraperError(f"non-JSON response from {url}: {exc}") from exc


def _to_int(value: Any) -> int:
    if value is None:
        return 0
    try:
        return round(float(value))
    except (TypeError, ValueError):
        return 0


def _normalize_date(raw: str | None) -> str | None:
    """ESPN dates look like "2026-09-13T13:00Z" (no seconds). Normalize to
    "YYYY-MM-DDTHH:MM:SSZ" to match PLAN.md's documented shape. Falls back
    to the raw string if it doesn't parse (best-effort, never fatal)."""
    if not raw:
        return raw
    body = raw.removesuffix("Z")
    for fmt in ("%Y-%m-%dT%H:%M:%S", "%Y-%m-%dT%H:%M"):
        try:
            dt = datetime.strptime(body, fmt).replace(tzinfo=timezone.utc)
            return dt.strftime("%Y-%m-%dT%H:%M:%SZ")
        except ValueError:
            continue
    return raw


def format_season(year: int | None) -> str:
    """ESPN gives a season as a single start year (int), e.g. 2026 for the
    2026-27 season. PLAN.md's documented `season` shape is "2025-26"."""
    if year is None:
        return ""
    return f"{year}-{(year + 1) % 100:02d}"


# --------------------------------------------------------------------------
# standings
# --------------------------------------------------------------------------


def scrape_standings(espn_league: str) -> tuple[dict[str, Any], str]:
    """Returns (data, season) where `data` matches PLAN.md's standings shape:
    {"table": [{rank, team, team_id, played, won, drawn, lost, goals_for,
    goals_against, goal_diff, points, form}, ...]}
    """
    url = f"{ESPN_API_BASE}/apis/v2/sports/soccer/{espn_league}/standings"
    payload = _get_json(url)

    try:
        standings = payload["children"][0]["standings"]
        entries = standings["entries"]
    except (KeyError, IndexError, TypeError) as exc:
        raise ScraperError(f"unexpected standings payload shape: {exc}") from exc

    table = []
    for entry in entries:
        stats = {s.get("name"): s.get("value") for s in entry.get("stats", [])}
        team = entry.get("team", {}) or {}
        table.append(
            {
                "rank": _to_int(stats.get("rank")),
                "team": team.get("displayName") or team.get("name") or "",
                "team_id": str(team.get("id")) if team.get("id") is not None else "",
                "played": _to_int(stats.get("gamesPlayed")),
                "won": _to_int(stats.get("wins")),
                "drawn": _to_int(stats.get("ties")),
                "lost": _to_int(stats.get("losses")),
                "goals_for": _to_int(stats.get("pointsFor")),
                "goals_against": _to_int(stats.get("pointsAgainst")),
                "goal_diff": _to_int(stats.get("pointDifferential")),
                "points": _to_int(stats.get("points")),
                # ESPN's standings API does not expose a recent-results
                # streak; left empty rather than fabricated. See README.
                "form": [],
            }
        )
    table.sort(key=lambda row: row["rank"] or 0)

    season = format_season(standings.get("season"))
    return {"table": table}, season


# --------------------------------------------------------------------------
# fixtures / results (both come from the same "scoreboard" endpoint)
# --------------------------------------------------------------------------


def _fetch_scoreboard_events(
    espn_league: str, date_from: datetime, date_to: datetime
) -> list[dict[str, Any]]:
    url = f"{ESPN_API_BASE}/apis/site/v2/sports/soccer/{espn_league}/scoreboard"
    params = {"dates": f"{date_from:%Y%m%d}-{date_to:%Y%m%d}"}
    payload = _get_json(url, params=params)
    return payload.get("events", []) or []


def _home_away(competition: dict[str, Any]) -> tuple[dict[str, Any], dict[str, Any]]:
    competitors = competition.get("competitors", []) or []
    home = next((c for c in competitors if c.get("homeAway") == "home"), {}) or {}
    away = next((c for c in competitors if c.get("homeAway") == "away"), {}) or {}
    return home, away


def _event_state(event: dict[str, Any]) -> str:
    return (
        (event.get("status") or {}).get("type", {}).get("state") or ""
    ).lower()


def _team_name(side: dict[str, Any]) -> str:
    team = side.get("team") or {}
    return team.get("displayName") or team.get("name") or ""


def scrape_fixtures(espn_league: str) -> tuple[dict[str, Any], str]:
    """Upcoming (not-yet-played) matches. Matches PLAN.md's fixtures shape:
    {"fixtures": [{event_id, date, home_team, away_team, venue, status}]}
    """
    now = datetime.now(timezone.utc)
    events = _fetch_scoreboard_events(
        espn_league, now, now + timedelta(days=FIXTURES_WINDOW_DAYS)
    )

    fixtures = []
    season_year = None
    for event in events:
        if _event_state(event) != "pre":
            continue
        try:
            competition = event["competitions"][0]
        except (KeyError, IndexError, TypeError):
            continue
        home, away = _home_away(competition)
        fixtures.append(
            {
                "event_id": str(event.get("id") or ""),
                "date": _normalize_date(event.get("date")),
                "home_team": _team_name(home),
                "away_team": _team_name(away),
                "venue": (competition.get("venue") or {}).get("fullName"),
                "status": "scheduled",
            }
        )
        season_year = season_year or (event.get("season") or {}).get("year")

    return {"fixtures": fixtures}, format_season(season_year)


def scrape_results(espn_league: str) -> tuple[dict[str, Any], str]:
    """Recently completed matches. Matches PLAN.md's results shape:
    {"results": [{event_id, date, home_team, away_team, home_score,
    away_score, status}]}
    """
    now = datetime.now(timezone.utc)
    events = _fetch_scoreboard_events(
        espn_league, now - timedelta(days=RESULTS_WINDOW_DAYS), now
    )

    results = []
    season_year = None
    for event in events:
        if _event_state(event) != "post":
            continue
        try:
            competition = event["competitions"][0]
        except (KeyError, IndexError, TypeError):
            continue
        home, away = _home_away(competition)
        results.append(
            {
                "event_id": str(event.get("id") or ""),
                "date": _normalize_date(event.get("date")),
                "home_team": _team_name(home),
                "away_team": _team_name(away),
                "home_score": _to_int(home.get("score")),
                "away_score": _to_int(away.get("score")),
                "status": "final",
            }
        )
        season_year = season_year or (event.get("season") or {}).get("year")

    return {"results": results}, format_season(season_year)


# --------------------------------------------------------------------------
# dispatcher
# --------------------------------------------------------------------------

_SCRAPERS = {
    "standings": scrape_standings,
    "fixtures": scrape_fixtures,
    "results": scrape_results,
}


def scrape(data_type: str, espn_league: str) -> tuple[dict[str, Any], str]:
    """Dispatch to the right scrape_* function. Returns (data, season).
    Raises ScraperError on any failure; raises ValueError for an unknown
    data_type (caller is expected to have already validated it)."""
    try:
        fn = _SCRAPERS[data_type]
    except KeyError as exc:
        raise ValueError(f"unknown data_type: {data_type}") from exc
    return fn(espn_league)

"""
The two internal endpoints from PLAN.md's "Contract: api-gateway <->
scraper-service" section:

  GET  /internal/espn/<league>/<data_type>/latest?max_age_seconds=300
  POST /internal/espn/<league>/<data_type>/refresh
"""

from __future__ import annotations

from datetime import datetime, timezone

from django.conf import settings
from django.http import JsonResponse
from django.views.decorators.csrf import csrf_exempt
from django.views.decorators.http import require_http_methods

from . import espn, mongo
from .config import LEAGUE_TO_ESPN_CODE, is_supported
from .exceptions import ScraperError

DEFAULT_MAX_AGE_SECONDS = getattr(settings, "SCRAPER_STALENESS_SECONDS", 300)


def _iso(dt: datetime) -> str:
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=timezone.utc)
    return dt.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def _unsupported_response(league: str, data_type: str) -> JsonResponse:
    return JsonResponse(
        {
            "error": "unsupported",
            "detail": f"unsupported league={league!r} or data_type={data_type!r}",
        },
        status=400,
    )


def _doc_response(doc: dict, max_age_seconds: int) -> dict:
    scraped_at = doc["scraped_at"]
    if scraped_at.tzinfo is None:
        scraped_at = scraped_at.replace(tzinfo=timezone.utc)
    age_seconds = int((datetime.now(timezone.utc) - scraped_at).total_seconds())
    return {
        "league": doc["league"],
        "data_type": doc["data_type"],
        "season": doc.get("season", ""),
        "scraped_at": _iso(scraped_at),
        "age_seconds": age_seconds,
        "stale": age_seconds > max_age_seconds,
        "data": doc.get("data"),
    }


def _parse_max_age(request) -> int:
    raw = request.GET.get("max_age_seconds")
    if raw is None:
        return DEFAULT_MAX_AGE_SECONDS
    try:
        return int(raw)
    except (TypeError, ValueError):
        return DEFAULT_MAX_AGE_SECONDS


@require_http_methods(["GET"])
def latest(request, league: str, data_type: str):
    if not is_supported(league, data_type):
        return _unsupported_response(league, data_type)

    store = mongo.get_store()
    doc = store.get(league, data_type)
    if doc is None:
        return JsonResponse(
            {"error": "not_found", "league": league, "data_type": data_type},
            status=404,
        )

    max_age_seconds = _parse_max_age(request)
    return JsonResponse(_doc_response(doc, max_age_seconds), status=200)


@csrf_exempt
@require_http_methods(["POST"])
def refresh(request, league: str, data_type: str):
    if not is_supported(league, data_type):
        return _unsupported_response(league, data_type)

    espn_league = LEAGUE_TO_ESPN_CODE[league]

    try:
        data, season = espn.scrape(data_type, espn_league)
    except ScraperError as exc:
        return JsonResponse(
            {
                "error": "scrape_failed",
                "league": league,
                "data_type": data_type,
                "detail": str(exc),
            },
            status=502,
        )

    store = mongo.get_store()
    scraped_at = mongo.utcnow()
    doc = store.upsert(league, data_type, season, scraped_at, data)

    return JsonResponse(_doc_response(doc, max_age_seconds=DEFAULT_MAX_AGE_SECONDS), status=200)

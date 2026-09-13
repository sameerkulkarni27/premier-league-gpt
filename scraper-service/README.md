# scraper-service

Django service that scrapes ESPN for Premier League standings/fixtures/
results, caches the results in MongoDB (`pitchquery.espn_snapshots`), and
exposes the two internal HTTP endpoints `api-gateway` calls. See the root
`PLAN.md` for the full contract (Mongo schema, endpoint shapes, staleness
semantics) — this README only covers running/testing this service and
notes on the ESPN scraping itself.

## Layout

```
scraper-service/
  manage.py
  scraper_service/        Django project (settings, urls, wsgi/asgi)
  scraper/                the actual app
    espn.py               ESPN scraper (standings/fixtures/results) — pluggable
    mongo.py               SnapshotStore: pymongo wrapper around espn_snapshots
    views.py               GET .../latest, POST .../refresh
    config.py              supported league/data_type values
    exceptions.py          ScraperError
    tests/                 unit tests (HTTP + Mongo both mocked)
  requirements.txt         runtime deps
  requirements-dev.txt     + mongomock, for tests
  Dockerfile
```

## Setup

```bash
cd scraper-service
python -m venv .venv
./.venv/Scripts/activate        # Windows; source .venv/bin/activate on macOS/Linux
pip install -r requirements-dev.txt
```

Config is read from environment variables, loaded from the repo-root
`.env` in local dev (already present, gitignored — see root `.gitignore`):

- `ESPN_COOKIE_HEADER` — optional raw browser `Cookie` header, attached
  best-effort on outbound ESPN requests.
- `MONGO_URI` — Mongo connection string (default `mongodb://localhost:27017`).
- `SCRAPER_STALENESS_SECONDS` — default `max_age_seconds` (default `300`).

## Running

```bash
python manage.py runserver 0.0.0.0:8001
```

Requires a reachable MongoDB at `MONGO_URI` for `/latest` and `/refresh`
to work (agent-infra's docker-compose wires this up for local dev). The
service does not open a Mongo connection until the first request that
needs one.

## Testing

```bash
python manage.py test scraper -v 2
```

All 23 tests mock the HTTP layer (`requests.get`) for the scraper and use
`mongomock` in place of a real `pymongo.MongoClient` for the store/view
tests — nothing here hits real ESPN or a real Mongo instance, so it's
CI-safe. Covers:

- `scraper/tests/test_espn.py` — parses real (captured) ESPN JSON shapes
  into the exact `data` shapes from PLAN.md; error paths (bad shape,
  network failure); cookie header attached only when configured.
- `scraper/tests/test_mongo.py` — `SnapshotStore` upsert is one document
  per `(league, data_type)` (the whole point of the unique compound
  index), independent documents per `data_type`, index actually created.
- `scraper/tests/test_views.py` — `/latest` 404/200/staleness math/400 for
  unsupported input; `/refresh` 200 (and upserts into the store)/502 on
  scrape failure (and does *not* leave a doc behind)/400 for unsupported.

## ESPN bot-detection (read this before "fixing" the scraper)

`scraper/espn.py` targets ESPN's public JSON "site API"
(`site.api.espn.com/apis/...`) rather than parsing rendered HTML, because
`www.espn.com` itself sits behind an **AWS WAF JavaScript challenge** —
confirmed during development: even with `ESPN_COOKIE_HEADER` attached,
`GET https://www.espn.com/soccer/standings/_/league/eng.1` returns HTTP
202 with a `challenge.js` / `AwsWafIntegration` page, not the real HTML.
No plain HTTP client can pass this without executing JS, so HTML scraping
of `www.espn.com` was ruled out.

The `site.api.espn.com` JSON endpoints are **not** behind that specific
challenge and did return real data during development (verified live:
`GET /apis/v2/sports/soccer/eng.1/standings` and
`GET /apis/site/v2/sports/soccer/eng.1/scoreboard?dates=...` both returned
200 with the JSON shapes `scraper/espn.py` and
`scraper/tests/fixtures.py` are based on). However, this API sits behind
Akamai, and repeated requests from the same IP during development started
returning a generic Akamai "Access Denied" 403 (`errors.edgesuite.net`)
after a handful of calls — with or without the cookie header, and
regardless of HTTP client (`urllib`/`requests`). This looks like
IP/volume-based rate limiting rather than a per-request check, so:

- Treat scraping as best-effort and rate-limit callers in production
  (this is exactly what the Mongo cache + `SCRAPER_STALENESS_SECONDS` /
  `/latest`-then-`/refresh` dance in PLAN.md is for — don't call
  `/refresh` on every request).
- A 502 `scrape_failed` from `/refresh` is an expected, handled outcome
  when ESPN blocks a request, not a bug — `api-gateway` should be
  prepared for it.
- If this gets worse in practice, the next things to try (not yet done
  here): rotating/realistic outbound IPs, a headless-browser fallback for
  `www.espn.com` (defeats the WAF's JS challenge properly), or backing
  off with retries/jitter across the two ESPN hosts.

`scraper/espn.py` is intentionally self-contained (one dispatcher
function, `scrape(data_type, espn_league)`) so a different scraping
strategy — headless browser, a different upstream, etc. — can be swapped
in later without touching `views.py` or `mongo.py`.

## Known deviations / calls made beyond PLAN.md's exact wording

- **`form` is always `[]`.** ESPN's standings API
  (`/apis/v2/sports/soccer/<code>/standings`) does not expose a
  recent-results streak per team (checked the full `stats` list on a live
  response — no `form`/`streak`-like field exists). Rather than fabricate
  it, `standings.table[].form` is always an empty list. If this matters,
  it would need a second scrape (e.g. deriving each team's last-5 results
  from the `results` data type) — flagged as an open question rather than
  guessed at.
- **`GET .../latest` also returns 400 `unsupported`** for an unsupported
  `league`/`data_type`, even though PLAN.md's `/latest` section only
  documents 200/404. This doesn't change any documented shape (the 400
  shape is copied from `/refresh`'s `unsupported` response) — it just
  covers an input-validation case PLAN.md didn't explicitly spec for this
  endpoint. Flagging this in case api-gateway's client code should
  special-case it.
- **Fixtures/results windows are fixed at 30 days** each direction
  (`scraper/espn.py`'s `FIXTURES_WINDOW_DAYS` / `RESULTS_WINDOW_DAYS`)
  because ESPN's scoreboard endpoint takes an explicit date range, not a
  "give me the next N events" query. Not in PLAN.md; seemed like a
  reasonable default rather than something to block on.

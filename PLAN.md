# Pitch Query — Plan

Natural-language Q&A over live Premier League data. User asks a question in
the frontend → api-gateway runs an LLM tool-calling loop → the LLM calls
`get_latest_espn_data` → api-gateway fetches from scraper-service (which owns
the ESPN scrape + Mongo cache) → LLM returns structured JSON → frontend
renders it.

Note: no pre-existing ESPN scraper was supplied. `scraper-service` builds one
from scratch against ESPN's public soccer endpoints (standings/fixtures/
results pages under espn.com/soccer/...), reading an optional
`ESPN_COOKIE_HEADER` env var to attach as a `Cookie` header on outbound
requests (fallback for bot-detection friction — most of these pages don't
require auth). That value lives only in the local, gitignored `.env` and in
GitHub Actions secrets — never in code or commits.

## Service boundaries

| Service | Stack | Owns | Never touches |
|---|---|---|---|
| `frontend/` | React + Redux Toolkit | Chat UI, rendering `/api/ask` responses | api-gateway internals, Mongo, ESPN |
| `api-gateway/` | Spring Boot | `POST /api/ask`, OpenAI tool-calling loop, calling scraper-service over HTTP | Mongo (no direct DB access), ESPN (no direct scraping) |
| `scraper-service/` | Django | ESPN scraping, all MongoDB reads/writes, staleness logic | LLM calls, frontend rendering |
| `infra/` | Docker Compose, GitHub Actions | Local dev orchestration, CI, secrets wiring | Application logic in any service |

Hard rule: a task/agent only edits files under its own service directory
(plus root-level files it's explicitly told to touch, e.g. `infra` editing
`docker-compose.yml` at root if needed). Any cross-cutting contract change
comes back to the orchestrator to update this file.

## Supported values (v1)

- `league`: `"premier-league"` (schema is league-agnostic so more can be added later)
- `data_type`: `"standings" | "fixtures" | "results"`

## MongoDB schema (owned exclusively by scraper-service)

Database: `pitchquery`. Single collection: **`espn_snapshots`**.

```
{
  _id: ObjectId,
  league: "premier-league",        // string, indexed
  data_type: "standings",          // "standings" | "fixtures" | "results", indexed
  season: "2025-26",               // string
  scraped_at: ISODate,             // UTC timestamp of last successful scrape
  data: { ... }                    // shape below, per data_type
}
```

Unique compound index: `{ league: 1, data_type: 1 }` (one document per
league+data_type; each scrape upserts in place).

### `data` shapes

**standings**
```json
{
  "table": [
    {
      "rank": 1, "team": "Liverpool", "team_id": "364",
      "played": 5, "won": 4, "drawn": 1, "lost": 0,
      "goals_for": 12, "goals_against": 3, "goal_diff": 9,
      "points": 13, "form": ["W", "W", "D", "W", "W"]
    }
  ]
}
```

**fixtures**
```json
{
  "fixtures": [
    {
      "event_id": "678889", "date": "2026-09-20T14:00:00Z",
      "home_team": "Arsenal", "away_team": "Chelsea",
      "venue": "Emirates Stadium", "status": "scheduled"
    }
  ]
}
```

**results**
```json
{
  "results": [
    {
      "event_id": "678870", "date": "2026-09-13T14:00:00Z",
      "home_team": "Man City", "away_team": "Everton",
      "home_score": 3, "away_score": 1, "status": "final"
    }
  ]
}
```

## Contract: api-gateway ↔ scraper-service

Base URL: `SCRAPER_SERVICE_BASE_URL` (default `http://localhost:8001`).

### `GET /internal/espn/<league>/<data_type>/latest?max_age_seconds=300`

Reads cache only, never scrapes.

- **200** (cache hit):
  ```json
  {
    "league": "premier-league", "data_type": "standings",
    "season": "2025-26", "scraped_at": "2026-09-13T18:30:00Z",
    "age_seconds": 42, "stale": false,
    "data": { "table": [ ... ] }
  }
  ```
  `stale` is `true` when `age_seconds > max_age_seconds` (caller decides
  whether to then call `/refresh`).
- **404** (never scraped yet):
  ```json
  { "error": "not_found", "league": "premier-league", "data_type": "standings" }
  ```

### `POST /internal/espn/<league>/<data_type>/refresh`

Body: `{}` (empty). Forces a re-scrape, upserts into Mongo, returns the fresh
document.

- **200**: same shape as `/latest`, with `stale: false` and `scraped_at: now`.
- **502** (scrape failed):
  ```json
  { "error": "scrape_failed", "league": "premier-league", "data_type": "standings", "detail": "..." }
  ```
- **400**: unsupported `league`/`data_type`:
  ```json
  { "error": "unsupported", "detail": "..." }
  ```

api-gateway's tool implementation calls `/latest` first; if `404` or
`stale: true`, it calls `/refresh` and uses that result instead. The LLM
never sees this two-step dance — it just gets fresh data back from one tool
call.

## LLM tool schema (OpenAI function calling, in api-gateway)

```json
{
  "type": "function",
  "function": {
    "name": "get_latest_espn_data",
    "description": "Fetch the latest data for a league from ESPN (standings table, upcoming fixtures, or recent results). Always returns fresh-enough data — refreshes automatically if the cache is stale or missing.",
    "parameters": {
      "type": "object",
      "properties": {
        "league": {
          "type": "string",
          "enum": ["premier-league"],
          "description": "League slug"
        },
        "data_type": {
          "type": "string",
          "enum": ["standings", "fixtures", "results"],
          "description": "Which kind of data to fetch"
        }
      },
      "required": ["league", "data_type"],
      "additionalProperties": false
    }
  }
}
```

Tool result message content sent back to the LLM (stringified JSON): the
`data` object from scraper-service, plus `scraped_at` and `season`. The
system prompt instructs the LLM to call this tool as needed, then produce
**one final JSON object** (no prose) matching the `/api/ask` response shape
below.

## Contract: frontend ↔ api-gateway

### `POST /api/ask`

Request:
```json
{ "question": "Where does Arsenal stand in the Premier League table?" }
```

Response — **200**:
```json
{
  "answer_type": "standings",
  "league": "premier-league",
  "summary": "Arsenal are 3rd in the Premier League with 11 points from 5 games.",
  "data": { "table": [ ... ] },
  "generated_at": "2026-09-13T19:05:00Z"
}
```
- `answer_type`: `"standings" | "fixtures" | "results" | "text"`. `"text"` is
  for questions that don't map to structured data (e.g. "who is Arsenal's
  manager?") — `data` is `null` in that case and the frontend just renders
  `summary` as text.
- `data`, when non-null, matches the corresponding shape from the Mongo
  schema section above (so the frontend can reuse the same table/card
  renderers scraper-service and api-gateway already agree on).
- `generated_at` is set by api-gateway (server time), not the LLM.

Response — **4xx/5xx**:
```json
{ "error": "message describing what went wrong" }
```

## Task breakdown (one per service, non-overlapping directories)

1. **agent-scraper** → `scraper-service/` — Django project, ESPN scraper
   (standings/fixtures/results for Premier League), Mongo integration
   (pymongo/mongoengine), the two internal endpoints, staleness config via
   `SCRAPER_STALENESS_SECONDS` env var (default 300), unit tests with a
   mocked HTTP layer for the scraper.
2. **agent-gateway** → `api-gateway/` — Spring Boot app, `POST /api/ask`,
   OpenAI tool-calling loop (`OPENAI_API_KEY`, `OPENAI_MODEL` env vars), HTTP
   client to `scraper-service`, response validation against the contract
   above, tests with a mocked scraper-service and mocked OpenAI client.
3. **agent-frontend** → `frontend/` — React + Redux Toolkit, chat input,
   `askQuestion` thunk calling `POST /api/ask`, renderers: table for
   `standings`, cards for `fixtures`/`results`, plain text bubble for
   `text`. Can start once this file's `/api/ask` shape is fixed (it already
   is) — does not need to wait on api-gateway's implementation.
4. **agent-infra** → `infra/` (+ root `docker-compose.yml`, `.github/workflows/`)
   — Compose file for mongo + all three services for local dev, GitHub
   Actions: per-service lint/test on path filters, docker-build on merge to
   main, a docs-drift check job. Wires `ESPN_COOKIE_HEADER`/`OPENAI_API_KEY`
   as GitHub Actions secrets (never committed) and documents the local
   `.env` keys in `infra/README.md` (no values).
5. **agent-integration** (spun up last, after 1–4 merge) → root-level
   `e2e/` — end-to-end tests hitting real service boundaries
   (docker-compose up, then exercise `/api/ask` → scraper-service → Mongo),
   fixes integration bugs only, no new features.

## Branching

`git branch feat/<task-slug> main` + `git worktree add ../pitch-query-<task-slug> feat/<task-slug>`
per task. Slugs: `scraper-service`, `api-gateway`, `frontend`, `infra`,
`integration-tests`.

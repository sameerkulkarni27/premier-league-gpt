# Tasks

Tracked by the orchestrator. Update on every state change.

| # | Slug | Service dir | Branch | Worktree path | Status | PR |
|---|---|---|---|---|---|---|
| 1 | `scraper-service` | `scraper-service/` | `feat/scraper-service` | `../pitch-query-scraper-service` | reviewed, ready to push (needs user OK) | - |
| 2 | `api-gateway` | `api-gateway/` | `feat/api-gateway` | `../pitch-query-api-gateway` | reviewed, ready to push (needs user OK) | - |
| 3 | `frontend` | `frontend/` | `feat/frontend` | `../pitch-query-frontend` | reviewed, ready to push (needs user OK) | - |
| 4 | `infra` | `infra/` (+ root compose/workflows) | `feat/infra` | `../pitch-query-infra` | reviewed, ready to push (needs user OK) | - |
| 5 | `integration-tests` | `e2e/` | `feat/integration-tests` | `../pitch-query-integration-tests` | not started (waits on 1-4 merging) | - |

Statuses: `pending` → `in progress` → `review` (PR open, orchestrator
reviewing diff against PLAN.md) → `merged`.

## Open contract questions

- `standings.table[].form` (PLAN.md example shows `["W","W","D","W","W"]`):
  ESPN's standings JSON API has no per-team recent-form field. agent-scraper
  ships it as `[]` always rather than fabricating data. Leaving as-is unless
  we want to derive it from the results collection later (out of scope for
  now).
- `GET /latest` now also returns `400 unsupported` for a bad league/data_type
  (PLAN.md only documented 200/404 for `/latest`). Non-breaking addition,
  reuses the `/refresh` error shape — approved, no PLAN.md edit needed.
- ESPN's rendered HTML pages are behind an AWS WAF JS challenge; agent-scraper
  pivoted to `site.api.espn.com`'s public JSON endpoints instead (not behind
  that challenge). Those endpoints started 403'ing under repeated dev
  traffic (looks like Akamai rate limiting, not a hard block) — expect
  `POST /refresh` to occasionally return 502 `scrape_failed` in practice;
  api-gateway's tool should lean on the Mongo cache rather than refreshing
  aggressively.

## Log

- 2026-09-13: PLAN.md written. Decisions: scraper built from scratch (no
  pre-existing module), OpenAI for the LLM tool-calling loop, ESPN cookie
  header kept in local gitignored `.env` only (never committed / never sent
  to GitHub Actions by this session — user to add that secret themselves).
  Spawning tasks 1-4 in parallel since contracts are already fixed in
  PLAN.md.

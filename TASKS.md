# Tasks

Tracked by the orchestrator. Update on every state change.

| # | Slug | Service dir | Branch | Worktree path | Status | PR |
|---|---|---|---|---|---|---|
| 1 | `scraper-service` | `scraper-service/` | `feat/scraper-service` | `../pitch-query-scraper-service` | pending | - |
| 2 | `api-gateway` | `api-gateway/` | `feat/api-gateway` | `../pitch-query-api-gateway` | pending | - |
| 3 | `frontend` | `frontend/` | `feat/frontend` | `../pitch-query-frontend` | pending | - |
| 4 | `infra` | `infra/` (+ root compose/workflows) | `feat/infra` | `../pitch-query-infra` | pending | - |
| 5 | `integration-tests` | `e2e/` | `feat/integration-tests` | `../pitch-query-integration-tests` | not started (waits on 1-4 merging) | - |

Statuses: `pending` → `in progress` → `review` (PR open, orchestrator
reviewing diff against PLAN.md) → `merged`.

## Open contract questions

None yet.

## Log

- 2026-09-13: PLAN.md written. Decisions: scraper built from scratch (no
  pre-existing module), OpenAI for the LLM tool-calling loop, ESPN cookie
  header kept in local gitignored `.env` only (never committed / never sent
  to GitHub Actions by this session — user to add that secret themselves).
  Spawning tasks 1-4 in parallel since contracts are already fixed in
  PLAN.md.

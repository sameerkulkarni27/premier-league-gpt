# Pitch Query — infra

Local dev orchestration (Docker Compose) and CI (GitHub Actions) for Pitch
Query. See the repo-root `PLAN.md` for the full architecture and the
service contracts these pieces wire together.

## Running locally with Docker Compose

1. Copy the env template and fill in real values:

   ```
   cp .env.example .env
   ```

   `.env` lives at the repo root, next to `docker-compose.yml`. It's
   gitignored — never commit it.

2. Start everything:

   ```
   docker compose up --build
   ```

   This brings up, in dependency order:
   - `mongo` (official image, named volume `mongo-data`, healthcheck-gated)
   - `scraper-service` (Django, port 8001) — waits for Mongo to be healthy
   - `api-gateway` (Spring Boot, port 8080) — waits for scraper-service
   - `frontend` (React dev server, port 5173) — waits for api-gateway

3. Stop with `docker compose down` (add `-v` to also drop the Mongo volume
   and start from an empty database next time).

## Env vars you need to fill in

All variables the three services read are listed in `.env.example` at the
repo root, with placeholders/defaults. The ones that need a **real** value
before things actually work:

- `ESPN_COOKIE_HEADER` — optional; only needed if ESPN's bot detection
  starts blocking unauthenticated scrapes. Leave empty to start.
- `OPENAI_API_KEY` — required for api-gateway's tool-calling loop against
  `POST /api/ask`. Without it, api-gateway can't call the LLM.

Everything else (`MONGO_URI`, `SCRAPER_STALENESS_SECONDS`, `OPENAI_MODEL`,
`SCRAPER_SERVICE_BASE_URL`, `VITE_API_GATEWAY_BASE_URL`) already has a
working default for the docker-compose network in `.env.example` — you
normally don't need to change these for local dev.

This README intentionally never prints or references actual secret values
— only `.env.example`'s placeholders.

## GitHub Actions secrets (human action required)

`ESPN_COOKIE_HEADER` and `OPENAI_API_KEY` must be added as **repository
secrets** in GitHub (Settings → Secrets and variables → Actions) by someone
with admin access to the repo. This agent/session has no access to GitHub's
secrets settings and cannot do this step — a human needs to do it once,
before any CI job that would need real credentials (currently none of the
workflows in this repo need them, since `docker-build.yml` only builds
images locally in the runner and doesn't call ESPN or OpenAI — but wiring
them in now means future integration/e2e jobs can pick them up without
another round trip).

## CI workflows in `.github/workflows/`

- `scraper-service-ci.yml`, `api-gateway-ci.yml`, `frontend-ci.yml` — each
  triggers only on pushes/PRs touching its own service directory (path
  filters), and runs that service's lint + test.
- `docker-build.yml` — on push to `main` only, builds each service's Docker
  image (matrix job) to make sure the Dockerfile is valid. Doesn't push
  anywhere; there are no registry credentials configured for this repo.
- `docs-drift-check.yml` — a deliberately simple guardrail: greps each
  service's source for a handful of field names that `PLAN.md`'s contract
  sections say that service owns (e.g. `answer_type`, `data_type`,
  `scraped_at`). Fails with a clear message if a documented field has zero
  matches in the service that's supposed to own it. This is **not** a real
  JSON-schema diff tool — it's a cheap tripwire for the common case where a
  field gets renamed in code but the doc doesn't follow (or vice versa).

### Why these workflows won't go red before everything merges

This branch (`feat/infra`) only contains `infra/`, `docker-compose.yml`,
`.github/workflows/*`, and `.env.example` — it does not have
`scraper-service/`, `api-gateway/`, or `frontend/`, since those are being
built in parallel on their own branches. If the CI workflows assumed those
directories existed, every run on this branch (and on any PR before all
four service branches have merged) would fail for a reason that has
nothing to do with this branch's own changes.

Instead, every workflow above starts with a cheap presence check (e.g. "does
`scraper-service/requirements.txt` exist?") and skips the rest of the job
with a `::warning::` annotation when the service directory isn't there yet,
rather than failing. Once each service's task merges to `main` and brings
its own directory (and Dockerfile) along, the corresponding workflow starts
actually running lint/test/build for real — no further changes needed here.

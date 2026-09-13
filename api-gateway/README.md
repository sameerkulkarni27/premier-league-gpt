# api-gateway

Spring Boot service that exposes `POST /api/ask` for the Pitch Query frontend.
It runs an OpenAI tool-calling loop: the model may call `get_latest_espn_data`
as needed, api-gateway fetches that data from `scraper-service` over HTTP
(never touching Mongo or ESPN directly), and the model's final JSON answer is
validated and returned to the frontend. See the root `PLAN.md` for the full
contract this service implements.

## Stack

- Java 17, Spring Boot 3.3 (Maven, `spring-boot-starter-web` +
  `spring-boot-starter-actuator`)
- `org.springframework.web.client.RestClient` for both outbound HTTP clients
  (OpenAI chat-completions API, scraper-service's internal endpoints) --
  synchronous, no reactive stack needed for this service's load.
- JUnit 5 + Mockito (unit tests, mocked `OpenAiClient`/`ScraperServiceClient`)
  and WireMock (`HttpScraperServiceClientTest`, a real HTTP layer standing in
  for scraper-service). No test hits the real OpenAI API or a real
  scraper-service.

## Running locally

Requires JDK 17 and Maven 3.9+ on your PATH (the repo's root `.gitignore`
excludes `api-gateway/.mvn/`, so the Maven wrapper isn't committed here --
install Maven directly, e.g. via your OS package manager or
https://maven.apache.org/download.cgi).

```bash
mvn spring-boot:run
```

The service listens on port `8080`. Set the required env vars first (see
below) -- at minimum `OPENAI_API_KEY`, and `SCRAPER_SERVICE_BASE_URL` if
scraper-service isn't running on the default `http://localhost:8001`.

### Try it

```bash
curl -X POST http://localhost:8080/api/ask \
  -H "Content-Type: application/json" \
  -d '{"question": "Where does Arsenal stand in the Premier League table?"}'
```

## Environment variables

| Variable | Required | Default | Purpose |
|---|---|---|---|
| `OPENAI_API_KEY` | yes | - | OpenAI API key used for the chat-completions calls |
| `OPENAI_MODEL` | no | `gpt-4o` | Chat-completions model to use |
| `OPENAI_BASE_URL` | no | `https://api.openai.com/v1` | Override for testing against a proxy/mock |
| `SCRAPER_SERVICE_BASE_URL` | no | `http://localhost:8001` | Base URL of `scraper-service` |
| `SCRAPER_MAX_AGE_SECONDS` | no | `300` | `max_age_seconds` sent to scraper-service's `/latest` endpoint |
| `TOOL_LOOP_MAX_ITERATIONS` | no | `5` | Safety cap on tool-calling turns per question |

None of these are committed anywhere; set them in your shell or a local
(gitignored) `.env` loaded by however you run the service.

## Tests

```bash
mvn test
```

Covers: happy path (tool call -> validated structured answer), a plain-text
answer that skips the tool, the scraper-service 404-then-refresh and
stale-then-refresh fallbacks, scraper-service being completely unreachable,
a scraper-service 502 (`scrape_failed`), malformed/non-JSON LLM output
(one retry, then a `text`-answer fallback instead of a 500), and the
`/api/ask` contract's required-field/enum/`data`-nullability validation.

## Docker

```bash
docker build -t pitch-query-api-gateway .
docker run -p 8080:8080 \
  -e OPENAI_API_KEY=sk-... \
  -e SCRAPER_SERVICE_BASE_URL=http://scraper-service:8001 \
  pitch-query-api-gateway
```

Multi-stage build (Maven + JDK 17 to build the jar, JRE 17 to run it). The
root `docker-compose.yml` (owned by `infra/`) will reference this Dockerfile
for local orchestration alongside `scraper-service` and Mongo.

## Design notes

- `AskService` owns the tool-calling loop and the response validation; it
  never trusts the LLM for `generated_at` -- that's always stamped by the
  server right before the response is returned.
- If the model's final message isn't valid JSON (or fails validation:
  missing required fields, bad `answer_type`, non-null `data` for a `text`
  answer, etc.), the service asks the model once to reformat as strict JSON.
  If that still fails, it falls back to a `200` response with
  `answer_type: "text"` wrapping the raw content -- never a `500` for a
  parsing hiccup.
- `ScraperServiceClient`/`OpenAiClient` are interfaces specifically so tests
  can substitute mocks without any real network calls; `HttpScraperServiceClient`
  and `HttpOpenAiClient` are the only production implementations.
- Every failure path (bad request, scraper-service down/rejecting, OpenAI
  error, validation failure) returns `{"error": "..."}` via
  `GlobalExceptionHandler` -- never a raw stack trace to the frontend.

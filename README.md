# RM Pre-Meeting Intelligence Brief

Agentic AI service that generates a one-page pre-meeting brief for a bank Relationship Manager.
Backed by Plaid Sandbox data + a synthetic PostgreSQL CRM. All compute runs locally via Docker Compose.

## Quick start

```bash
cp .env.example .env
# fill in ANTHROPIC_API_KEY, PLAID_CLIENT_ID, PLAID_SECRET
docker compose up --build
curl http://localhost:8080/health
```

## Stack

| Layer | Technology |
|---|---|
| API | Java 21 / Spring Boot 4.x / Maven |
| Database | PostgreSQL 16 (schema via Flyway) |
| Cache | Redis 7 (optional, `app.cache.enabled=true`) |
| LLM | Anthropic Claude via `anthropic-java` SDK |
| Financial data | Plaid Sandbox via `plaid-java` |

> Docker images use JDK 21. Set `<java.version>26</java.version>` in `pom.xml` and update
> the Dockerfile `FROM` lines to `eclipse-temurin:26-jdk` / `eclipse-temurin:26-jre` for local
> builds on JDK 26.

## Services

| Port | Service |
|---|---|
| 8080 | brief-api (Spring Boot) |
| 5432 | postgres |
| 6379 | redis |

## API (current endpoints)

| Method | Path | Description |
|---|---|---|
| GET | /health | Health check |

*(More endpoints added each step — see spec section 8.)*

## Configuration

All thresholds live in `brief-api/src/main/resources/application.yml`:

```yaml
app:
  cache:
    enabled: false          # set true to enable Redis caching
  delta:
    large-outflow-threshold: 10000   # dollars
    dormancy-days: 90
  agent:
    eager-tool-calls: true  # false = agentic tool selection by Claude
```

## Build steps (spec section 11)

- [x] Step 1 — scaffold + Dockerfile + docker-compose + /health + Flyway schema
- [ ] Step 2 — CRM entities, repos, seed data, GET /clients
- [ ] Step 3 — Plaid integration + /admin/seed-client
- [ ] Step 4 — read tools (getAccounts, getTransactions, …)
- [ ] Step 5 — delta engine (ChangeReport)
- [ ] Step 6 — agent orchestrator + SSE streaming
- [ ] Step 7 — synthesis (final Claude call → brief JSON)
- [ ] Step 8 — thin UI
- [ ] Step 9 — hero demo scenario polish

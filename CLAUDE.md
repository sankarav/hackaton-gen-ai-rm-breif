# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & run

```bash
# Start everything (builds brief-api image from source)
docker compose up --build

# Rebuild only the API after code changes
docker compose build brief-api && docker compose up -d brief-api

# Health check
curl http://localhost:8080/health

# Compile locally (no Docker) — requires JDK 21+
cd brief-api && mvn -B package -DskipTests
```

There are **no unit tests** in this project — correctness is verified by running the app and hitting the endpoints listed below.

## Environment

Copy `.env.example` → `.env` and fill in three secrets before starting:

```
ANTHROPIC_API_KEY=sk-ant-...
PLAID_CLIENT_ID=...
PLAID_SECRET=...
PLAID_ENV=sandbox
```

`SPRING_DATASOURCE_URL`, `SPRING_DATA_REDIS_HOST` are injected by `docker-compose.yml` and must not be set in `.env`.

## Key endpoints

| Method | Path | Purpose |
|---|---|---|
| GET | `/clients` | List seeded clients for UI dropdown |
| POST | `/admin/seed-client` | Bootstrap a client's Plaid item; use `force:true` to re-seed |
| GET | `/briefs/{clientId}/stream` | SSE stream — starts the full agent pipeline |
| GET | `/debug/tools/{clientId}/{tool}` | Run a single tool and inspect raw output |
| GET | `/debug/delta/{clientId}` | Run deterministic delta and inspect ChangeReport |
| GET | `/health` | Liveness check |

### Hero demo reset

```bash
curl -X POST http://localhost:8080/admin/seed-client \
  -H "Content-Type: application/json" \
  -d '{"clientId":"client_002","persona":"user_good","plantLargeOutflow":false,"force":true}'
```

## Architecture

The pipeline runs in one HTTP request (SSE stream) through four sequential phases:

```
GET /briefs/{clientId}/stream
  └── AgentOrchestrator.run()
        ├── 1. GATHER  — 6 read tools called directly (eager) or via Claude tool-use (LLM mode)
        │     ├── CrmContextService   → Postgres (last meeting, notes, promises, synthetic products)
        │     └── PlaidToolService    → Plaid Sandbox (accounts, transactions, holdings, liabilities, identity)
        ├── 2. DELTA   — DeltaService.compute() — pure Java, no LLM
        │     └── Produces ChangeReport: balances, large outflows, maturity alerts, dormancy flag
        ├── 3. SYNTHESIZE — SynthesisService.synthesize()
        │     ├── Single Claude call (claude-sonnet-4-6) with ChangeReport + CRM context
        │     ├── Citation validator strips any IDs not in the ChangeReport before returning
        │     └── Returns BriefSchema (typed Java record matching spec §8 JSON schema)
        └── 4. STREAM  — BriefController emits SSE events for each phase transition
```

## Package layout

```
com.demo.rmbrief/
  agent/       AgentOrchestrator (gather loop), StepEmitter (SSE callback interface)
  tools/       PlaidToolService (5 Plaid read tools), CrmContextService, ToolResult record
  delta/       DeltaService (deterministic ChangeReport), ChangeReport record
  llm/         SynthesisService (final Claude call + citation validation), BriefSchema record
  plaid/       PlaidConfig (SDK wiring), PlaidBootstrapService (public_token exchange, seed)
  crm/         JPA entities (Client, Interaction, SyntheticProduct) + repositories
  web/         BriefController (SSE), ClientController, AdminController, ToolDebugController
  config/      FlywayConfig (explicit bean — required for Spring Boot 4), RedisConfig
```

Static UI is a single file at `brief-api/src/main/resources/static/index.html` (no build step).

## Hard constraints — never violate

- **Read-only Plaid.** No write/transfer/payment Plaid scopes or endpoints.
- **Deltas are deterministic Java.** `DeltaService` computes `ChangeReport`; the LLM is never asked to compute numbers or compare dates.
- **Citation validation.** `SynthesisService.validateCitations()` strips any citation ID that does not resolve to an `accountId`, `transactionId`, or `productType` from the `ChangeReport` before the brief is returned.
- **No invented numbers.** The synthesis prompt forbids Claude from referencing data not present in the supplied `ChangeReport` + CRM context JSON.

## Known Plaid sandbox quirks

- `sandboxTransactionsCreate` (custom transaction planting) does not reliably produce transactions visible in `transactionsSync` — planted transactions are silently dropped regardless of persona.
- `ApiClient` uses `"clientId"` / `"secret"` as map keys (not header names). Environment is set via `apiClient.setPlaidAdapter(ApiClient.Sandbox)` — `ApiClient.Development` does not exist.
- `plaid-java` version is **35.0.0**. `CustomSandboxTransaction` has no `accountId()` setter — per-account transaction targeting is unsupported.
- `Security.getType()` returns `String`, not an enum; do not call `.getValue()` on it.

## Spring Boot 4 / Java SDK gotchas

- **Flyway**: auto-configuration is unreliable in Spring Boot 4 — `FlywayConfig` creates an explicit `@Bean` that calls `flyway.migrate()` inline. Do not remove it.
- **`ddl-auto: none`** — Flyway owns the schema; Hibernate does no validation.
- **`ObjectMapper`** is not auto-wired in Spring Boot 4 — use `private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules()`.
- **`Map.of()` rejects null values** — use `Map<String, Object> m = new LinkedHashMap<>()` with explicit type (not `var`) when mapping Plaid response fields that may be null.
- **`anthropic-java` 2.41.0**: `AnthropicOkHttpClient.fromEnv()` reads `ANTHROPIC_API_KEY`. For multi-turn tool-use loops, use `params.toBuilder().addMessage(response).addUserMessageOfBlockParams(toolResults).build()` to extend the conversation. `StopReason` comparison: use `"tool_use".equals(response.stopReason().map(Object::toString).orElse(""))`.

## Configurable thresholds (application.yml)

```yaml
app:
  delta:
    large-outflow-threshold: 5000   # catches $5,850 GUSTO PAY; raise to filter noise
    dormancy-days: 90
  agent:
    eager-tool-calls: true          # false = Claude plans which tools to call
```

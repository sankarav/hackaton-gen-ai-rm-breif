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

**Corporate AI gateway:** set `SPRING_PROFILES_ACTIVE=gateway` plus `ANTHROPIC_GATEWAY_URL`, `ANTHROPIC_GATEWAY_API_KEY`, `ANTHROPIC_GATEWAY_KEY_HEADER` in `.env`. The `gateway` profile (bottom of `application.yml`) sends the key only in that header, never as `x-api-key`. All Claude calls share the one `AnthropicClient` bean from `AnthropicConfig` — never call `AnthropicOkHttpClient.fromEnv()` directly.

`MEDIA_ENCRYPTION_KEY` (base64 AES-256, `openssl rand -base64 32`) is also required — compose refuses to start without it. Changing it makes previously stored capture media unreadable.

## Key endpoints

| Method | Path | Purpose |
|---|---|---|
| GET | `/clients` | List seeded clients for UI dropdown |
| POST | `/admin/seed-client` | Bootstrap a client's Plaid item; use `force:true` to re-seed |
| GET | `/briefs/{clientId}/stream` | SSE stream — starts the full agent pipeline |
| GET | `/debug/tools/{clientId}/{tool}` | Run a single tool and inspect raw output |
| GET | `/debug/delta/{clientId}` | Run deterministic delta and inspect ChangeReport |
| GET | `/health` | Liveness check |
| POST | `/captures` | Multipart upload (notes / images / audio + `audioMode`, consent) — starts a capture workflow |
| GET | `/captures`, `/captures/{id}` | List / full draft (segments, items, flags, audit) |
| GET | `/captures/{id}/stream` | SSE state changes until review is needed or a final state |
| PUT | `/captures/{id}/items/{itemId}` | RM edits text / owner / date / Keep-Drop decision |
| POST | `/captures/{id}/approve` · `/reject` · `/withdraw-consent` | Sent to the workflow as Temporal signals |
| GET/PUT | `/policy/recording` · `/admin/recording-policy` | Compliance toggle for meeting recording (off by default) |
| GET/POST | `/clients/{id}/promises` · `/promises/{id}/status` | Tracked promises; mark DONE / CANCELLED |

UI: `/` (brief) and `/capture.html` (capture + review). Temporal Web UI: http://localhost:8233.

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
        │     ├── Single Claude call (`app.brief.model`, default claude-sonnet-5) with ChangeReport + CRM context
        │     ├── Citation validator strips any IDs not in the ChangeReport before returning
        │     └── Returns BriefSchema (typed Java record matching spec §8 JSON schema)
        └── 4. STREAM  — BriefController emits SSE events for each phase transition
```

### Post-meeting capture (Temporal workflow per capture, `capture/`)

```
POST /captures → CaptureService (validate, encrypt media, consent) → CaptureWorkflow "capture-{id}"
  normalize      notes → lines; photos → Claude vision; audio → TranscriptionProvider (Whisper)
  labelSpeakers  recordings only — Claude labels RM/CLIENT, every label marked speakerInferred
  extract        one Claude call → summary + items citing segment seqs (dates/products copied verbatim)
  resolve        Java: DateResolver, ProductResolver, flags (block approval) vs notes (informational)
  await review   signals approve / reject / withdrawConsent; reminder timer
  commit         Interaction + client_promise rows → feeds DeltaService.openPromises → brief
  retention      timer, then raw media deleted; consent withdrawal purges everything derived
```

Only capture IDs cross into Temporal; content stays in Postgres and the encrypted media store. Activities are idempotent (Temporal retries them). `TRANSCRIPTION_PROVIDER=openai` + `OPENAI_API_KEY` swaps Whisper for OpenAI.

## Package layout

```
com.demo.rmbrief/
  agent/       AgentOrchestrator (gather loop), StepEmitter (SSE callback interface)
  tools/       PlaidToolService (5 Plaid read tools), CrmContextService, ToolResult record
  delta/       DeltaService (deterministic ChangeReport), ChangeReport record
  llm/         SynthesisService (final Claude call + citation validation), BriefSchema record
  plaid/       PlaidConfig (SDK wiring), PlaidBootstrapService (public_token exchange, seed)
  crm/         JPA entities (Client, Interaction, SyntheticProduct) + repositories
  capture/     Capture entities + repos, CaptureService (API ops), NormalizeService, CaptureLifecycleService
    workflow/  CaptureWorkflow(+Impl), CaptureActivities(+Impl)
    transcribe/ TranscriptionProvider (LocalWhisper / OpenAI), ImageTranscriber (Claude vision)
    extract/   CaptureLlm (structured Claude calls), ExtractionService, SpeakerLabeler
    resolve/   DateResolver, ProductResolver, ResolveService — pure Java
    media/     MediaStore (AES-GCM encrypted files)
  web/         BriefController (SSE), ClientController, AdminController, ToolDebugController,
               CaptureController (SSE), PolicyController
  config/      FlywayConfig (explicit bean — required for Spring Boot 4), RedisConfig, TemporalConfig
```

Static UI is a single file at `brief-api/src/main/resources/static/index.html` (no build step).

## Hard constraints — never violate

- **Read-only Plaid.** No write/transfer/payment Plaid scopes or endpoints.
- **Deltas are deterministic Java.** `DeltaService` computes `ChangeReport`; the LLM is never asked to compute numbers or compare dates.
- **Citation validation.** `SynthesisService.validateCitations()` strips any citation ID that does not resolve to an `accountId`, `transactionId`, or `productType` from the `ChangeReport` before the brief is returned.
- **No invented numbers.** The synthesis prompt forbids Claude from referencing data not present in the supplied `ChangeReport` + CRM context JSON.
- **Nothing reaches the CRM without RM approval.** Captures commit only via the approve signal; flagged items must be decided first.
- **Capture dates/products are resolved in Java.** Claude copies phrases verbatim; `DateResolver`/`ProductResolver` interpret them. Extracted citations to non-existent segments are dropped and the item flagged.
- **Recording requires policy + consent.** `POST /captures` with `audioMode=RECORDING` is rejected unless the recording policy is on and consent is supplied.
- **Never log note/transcript content** — log IDs and counts only; `audit_log` stores actions, not content.

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
- **`anthropic-java` 2.40.1**: the client is built in `AnthropicConfig` from `app.anthropic.*` (base URL, key, optional custom key header). For multi-turn tool-use loops, use `params.toBuilder().addMessage(response).addUserMessageOfBlockParams(toolResults).build()` to extend the conversation. `StopReason` comparison: use `"tool_use".equals(response.stopReason().map(Object::toString).orElse(""))`.

- **Error messages**: Boot 4 uses `spring.web.error.include-message` (not `server.error.*`) to put `ResponseStatusException` reasons in the response body.

## Capture gotchas

- **JDK `HttpClient` must use HTTP/1.1** for the Whisper container — the default h2c upgrade makes uvicorn drop multipart bodies (HTTP 422).
- **Temporal dev server image runs as uid 1000** — its volume is mounted at `/home/temporal` so the SQLite file is writable.
- **Plaid sandbox items expire** (`ITEM_LOGIN_REQUIRED`) — re-seed with `force:true`. Capture still works; product matching falls back to CRM products.
- Shorten timers for demos: `CAPTURE_REVIEW_REMINDER`, `CAPTURE_RETENTION_NOTES`, `CAPTURE_RETENTION_RECORDING` (e.g. `45s`).
- Models are set in `application.yml`, never in code: `BRIEF_MODEL` (brief synthesis), `AGENT_MODEL` (tool planning when `eager-tool-calls=false`), `CAPTURE_MODEL` (capture vision, speaker labels, extraction). Newer models think by default and thinking is billed as output, so keep `maxTokens` generous (16000). Every Claude call logs `[llm] <purpose> model= in= out=` for cost tracking.

## Configurable thresholds (application.yml)

```yaml
app:
  delta:
    large-outflow-threshold: 5000   # catches $5,850 GUSTO PAY; raise to filter noise
    dormancy-days: 90
  agent:
    eager-tool-calls: true          # false = Claude plans which tools to call
    model: claude-sonnet-5          # AGENT_MODEL
  brief:
    model: claude-sonnet-5          # BRIEF_MODEL
```

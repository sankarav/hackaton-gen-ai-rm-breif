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

Copy `.env.example` → `.env` and fill in before starting:

```
ANTHROPIC_API_KEY=sk-ant-...
PLAID_CLIENT_ID=...
PLAID_SECRET=...
PLAID_ENV=sandbox
MEDIA_ENCRYPTION_KEY=...   # openssl rand -base64 32
```

`SPRING_DATASOURCE_URL`, `SPRING_DATA_REDIS_HOST`, `TEMPORAL_TARGET`, `WHISPER_URL`, `MEDIA_ROOT` are injected by `docker-compose.yml` and must not be set in `.env`. After editing `.env`, run `docker compose up -d brief-api` — `restart` does not re-read it.

On a fresh database, link each demo client to Plaid with the seed calls in the README (all four fields — `clientId`, `persona`, `plantLargeOutflow`, `force` — are required; a missing boolean returns 400).

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
| GET | `/captures/{id}/media/{mediaId}` | Decrypted photo/audio for review (410 after retention) |
| GET | `/briefs/saved?clientId=` | A client's saved briefs, newest first (UI reopens the latest) |
| GET | `/briefs/saved/{briefId}?version=` | Saved brief version + ChangeReport + citation details + version history |
| POST | `/briefs/saved/{briefId}/chat` | Chat turn, SSE over POST (`step`, `delta`, `proposal`, `answer`). Body: `{history, message}` |
| POST | `/briefs/saved/{briefId}/edits` | Apply an RM-approved proposal `{baseVersion, edit}` → new version (409 if stale) |

UI: `/` (brief + chat drawer) and `/capture.html` (capture + review). Temporal Web UI: http://localhost:8233.

### Hero demo reset

```bash
curl -X POST http://localhost:8080/admin/seed-client \
  -H "Content-Type: application/json" \
  -d '{"clientId":"client_002","persona":"user_good","plantLargeOutflow":false,"force":true}'
```

## Architecture

The brief pipeline runs in one HTTP request (SSE stream) through these sequential phases:

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
        ├── 4. SAVE    — BriefStore.create() stores it as version 1; SSE `brief_saved` returns the ID
        └── 5. STREAM  — BriefController emits SSE events for each phase transition
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

### Chat about a brief (`chat/`)

Each generated brief is saved (`brief` + immutable `brief_version`; SSE `brief_saved` gives the ID). Chat is **stateless on the server** — the browser sends text-only history each turn and nothing is persisted. `ChatService` runs a streaming tool-use loop (`CHAT_MODEL`), with instructions + brief context prompt-cached. `ChatTools` are read-only, bound to the brief's client, and compute every total/difference in Java. Answers cite `[[id]]`; IDs not returned by a tool or present in the brief are stripped. Brief changes are only *proposed* (`propose_brief_edit`); `BriefStore.apply` re-validates citations against live Plaid data and writes version n+1. Boundaries in the prompt: no investment/tax/legal advice, no client emails or letters.

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
  chat/        Brief/BriefVersion + repos, BriefStore (save/view/apply), BriefEdit, ChatTools, ChatService
  web/         BriefController (SSE), ClientController, AdminController, ToolDebugController,
               CaptureController (SSE), PolicyController, ChatController (SSE over POST)
  config/      FlywayConfig (explicit bean — required for Spring Boot 4), RedisConfig, TemporalConfig
```

Static UI is two files in `brief-api/src/main/resources/static/` (no build step): `index.html` (brief, saved briefs, versions, chat drawer) and `capture.html` (capture + review). They're baked into the image — rebuild after editing them.

Prompts and tool schemas are mirrored in `prompts/`. After changing a prompt or `ChatTools`, regenerate the mirrors with `prompts/extract-prompts.jsh` (instructions in `prompts/README.md`).

## Hard constraints — never violate

- **Read-only Plaid.** No write/transfer/payment Plaid scopes or endpoints.
- **Deltas are deterministic Java.** `DeltaService` computes `ChangeReport`; the LLM is never asked to compute numbers or compare dates.
- **Citation validation.** `SynthesisService.validateCitations()` strips any citation ID not in `ChangeReport.citationIds()` (`accountId`, `transactionId`, `productType`, `promiseId`) before the brief is returned. Chat strips `[[id]]` citations no tool returned; `BriefStore.apply` re-checks edit citations against live Plaid data.
- **Chat can't change anything on its own.** Brief edits are proposals; only `POST /briefs/saved/{id}/edits` (the RM's Apply) writes a new version. Chat tools are read-only and bound to the brief's client.
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
- Models are set in `application.yml`, never in code: `BRIEF_MODEL` (brief synthesis), `AGENT_MODEL` (tool planning when `eager-tool-calls=false`), `CHAT_MODEL` (brief chat), `CAPTURE_MODEL` (capture vision, speaker labels, extraction). Newer models think by default and thinking is billed as output, so keep `maxTokens` generous (16000). Every Claude call logs `[llm] <purpose> model= in= out=` for cost tracking.

## Chat gotchas

- **Streamed no-argument tool calls have no input.** `MessageAccumulator` leaves `ToolUseBlock._input()` missing (`JsonMissing`) when a tool with no parameters is called; converting or re-sending it throws "JsonMissing cannot be serialized". `ChatService.input()` and `assistantParam()` treat it as `{}`.
- **Return thinking blocks unchanged** when continuing a tool loop — rebuild the assistant turn with `ContentBlock.toParam()`, never edit or drop blocks.
- **Streamed model text is untrusted HTML input.** The chat renderer escapes first, then adds bold, bullets and tables; citation chips use `data-cite` attributes with a delegated click handler, never inline `onclick`.

## Demo data

- New permanent demo clients go in a Flyway migration written to be idempotent (`ON CONFLICT DO NOTHING` / `WHERE NOT EXISTS`), so it also applies where the rows were added by hand — see `V5__seed_client_004.sql`.
- Ad-hoc rows: use a `psql` heredoc (`<<'SQL'`) with `-v ON_ERROR_STOP=1` and `BEGIN`/`COMMIT` (example in the README).

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
  chat:
    model: claude-sonnet-5          # CHAT_MODEL
  capture:
    model: claude-sonnet-5          # CAPTURE_MODEL
    review-reminder: 48h            # CAPTURE_REVIEW_REMINDER
    retention: { notes: 30d, recording: 7d }
```

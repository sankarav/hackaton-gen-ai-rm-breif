# RM Pre-Meeting Intelligence Brief — Build Spec

> Hand this document to Claude Code as the authoritative spec. It describes a hackathon project: an agentic AI service that generates a one-page pre-meeting brief for a bank Relationship Manager (RM), grounded in financial data from the **Plaid Sandbox** and a small **synthetic CRM**. Everything runs locally via Docker Compose; the only external calls are to Plaid Sandbox APIs.

---

## 1. Goal

When an RM is about to meet a client, they enter a client ID and within seconds receive a one-page brief:

- **Snapshot** — name, segment, relationship tenure, total relationship value
- **What changed since last meeting** — new/notable transactions, balance moves, product changes (computed as a delta against `last_meeting_date`)
- **Watch-outs** — risk flags (large outflow, dormancy, low/declining balance, concentration)
- **Opportunities** — underutilized products, maturing instruments, loan payoff approaching
- **3 talking points** — concrete, data-grounded conversation starters
- **Last interaction recap** — what was discussed/promised last time

Every claim in the brief must cite its source data (e.g. `txn_id`, account id) to avoid hallucination.

## 2. Hard constraints

- **Read-only.** No write/transfer/payment Plaid endpoints. Only data-retrieval endpoints.
- **Local-first.** All services run in Docker Compose. The only outbound network calls are to Plaid Sandbox (`https://sandbox.plaid.com`).
- **Synthetic + sandbox data only.** No real bank APIs, no real customer data.
- **Agentic, not a single prompt.** A tool-calling loop where the LLM plans → calls read tools → computes deltas → synthesizes. Intermediate steps must be observable (logged/streamed) so the agentic behavior is demoable.

## 3. Tech stack

- **Language/Framework:** Java 26 + Spring Boot 4.x (matches the team's stack).
- **Build:** Maven.
- **LLM:** Anthropic Messages API (Claude). API key via env var `ANTHROPIC_API_KEY`. Use tool-calling.
- **Data source:** Plaid Sandbox (official Java client `plaid-java`).
- **Synthetic CRM:** PostgreSQL (seeded via Flyway migration + seed SQL).
- **Cache (optional, nice-to-have):** Redis to cache Plaid responses per access_token and avoid re-fetching during a demo.
- **Frontend:** minimal — a single static page (plain HTML+JS or a small React build) that takes a client ID and renders the streamed brief. Keep it thin; the backend is the star.
- **Containerization:** Docker (multi-stage build) + docker-compose.

## 4. High-level architecture

```
                             ┌──────────────────────────────-┐
   Browser (thin UI)  ───►   │        ws-brief-api           │   Spring Boot
   GET client list           │  (REST + SSE/WebSocket)       │
   POST /briefs/{clientId}   │                               │
                             │  ┌────────────────────────┐   │
                             │  │   Agent Orchestrator   │   │  tool-calling loop
                             │  │  plan→gather→delta→    │   │
                             │  │      synthesize        │   │
                             │  └───────────┬────────────┘   │
                             │     tools:   │                │
                             │  getAccounts │ getTransactions│
                             │  getHoldings │ getLiabilities │
                             │  getIdentity │ getCrmContext  │
                             └──────┬───────────────┬────────┘
                                    │               │
                   ┌────────────────▼──┐     ┌──────▼────────────┐
                   │  Plaid Sandbox    │     │   PostgreSQL      │
                   │  (external HTTPS) │     │ synthetic CRM     │
                   │  via plaid-java   │     │ (Flyway seeded)   │
                   └───────────────────┘     └───────────────────┘
                                    │
                          ┌─────────▼─────────┐
                          │  Anthropic API    │  (external HTTPS)
                          │  Claude tool-use  │
                          └───────────────────┘
```

Containers in docker-compose: `brief-api`, `postgres`, `redis` (optional), and a static `ui` (nginx serving the page) — or fold the UI into Spring Boot static resources to keep it to one app container.

## 5. The agentic flow (core logic)

Implement as an explicit loop, not one giant prompt:

1. **Plan** — Claude is given the client ID, the `last_meeting_date` (from CRM), and the tool catalog. It decides which tools to call. (For v1 you can also just call all tools eagerly; the *loop* is what makes later tool selection agentic — support both via a flag.)
2. **Gather** — Execute tool calls. Each tool returns structured JSON. Tools:
   - `getIdentity(clientId)` → Plaid `/identity/get`
   - `getAccounts(clientId)` → Plaid `/accounts/get` + `/accounts/balance/get`
   - `getTransactions(clientId, since)` → Plaid `/transactions/sync`
   - `getHoldings(clientId)` → Plaid `/investments/holdings/get`
   - `getLiabilities(clientId)` → Plaid `/liabilities/get`
   - `getCrmContext(clientId)` → Postgres (last_meeting_date, notes, rm_name, promises, segment, relationship_start_date)
3. **Delta computation** (deterministic Java, not the LLM) — compare current state vs `last_meeting_date`: new transactions, balance change per account, large outflows (configurable threshold, e.g. > $10k), dormancy (no txns in N days), maturing liabilities. Produce a structured `ChangeReport`. *Doing this in code keeps it accurate and gives the LLM grounded facts to write from.*
4. **Synthesize** — final Claude call: feed the gathered data + `ChangeReport` + CRM notes, ask for the brief in a strict JSON schema (sections + talking points + citations). Parse and render.
5. **Stream** — stream intermediate step messages to the UI ("Fetching accounts… found 4… noticed a $50k outflow on txn_4521, flagging…") via SSE/WebSocket so the agent's reasoning is visible.

## 6. Plaid Sandbox integration details

- **Bootstrap (no Link UI needed):**
  1. `POST /sandbox/public_token/create` with an institution (`ins_109508`, First Platypus Bank) and the products you need (`["transactions","identity","investments","liabilities"]`), optionally with a `username` persona (`user_good`, `user_yuppie`, `user_small_business`) or custom override.
  2. `POST /item/public_token/exchange` → `access_token`.
  3. Store the `access_token` mapped to your internal `clientId` (in Postgres). One client = one Plaid item.
- **Per client, on first setup**, run the bootstrap and persist the access_token. Provide a seeding script/endpoint `POST /admin/seed-client` that creates a client + Plaid item + CRM row in one shot.
- **Planting demo signals:**
  - `POST /sandbox/transactions/create` to inject a specific large outflow.
  - Use `inflow_model` (`monthly-income`, `monthly-balance-payment`, `monthly-interest-only-payment`) in custom user config for recurring patterns.
  - Maturing CD / instrument: model in the CRM/holdings synthetic layer since Plaid doesn't expose CD maturity cleanly.
- **Endpoints used (all read):** `/accounts/get`, `/accounts/balance/get`, `/transactions/sync`, `/identity/get`, `/investments/holdings/get`, `/liabilities/get`. Plus sandbox-only setup endpoints above.
- **Config:** `PLAID_CLIENT_ID`, `PLAID_SECRET`, `PLAID_ENV=sandbox`. Never commit secrets; read from env.

> Confirm current Plaid endpoint shapes against https://plaid.com/docs at build time — treat the names above as the spec, verify field names from the official `plaid-java` client.

## 7. Synthetic CRM schema (Postgres)

```sql
CREATE TABLE client (
  client_id            VARCHAR PRIMARY KEY,
  full_name            VARCHAR NOT NULL,
  segment              VARCHAR,              -- e.g. 'Mass Affluent','Private'
  relationship_start   DATE,
  rm_name              VARCHAR,
  plaid_access_token   VARCHAR,              -- mapped Plaid item
  plaid_item_id        VARCHAR
);

CREATE TABLE interaction (
  id             BIGSERIAL PRIMARY KEY,
  client_id      VARCHAR REFERENCES client(client_id),
  meeting_date   DATE NOT NULL,
  notes          TEXT,
  promises       TEXT                       -- JSON array of follow-ups promised
);

CREATE TABLE synthetic_product (             -- products Plaid won't model (e.g. CD)
  id             BIGSERIAL PRIMARY KEY,
  client_id      VARCHAR REFERENCES client(client_id),
  product_type   VARCHAR,                    -- 'CD','TERM_DEPOSIT', etc.
  balance        NUMERIC,
  maturity_date  DATE,
  rate           NUMERIC
);
```

Seed 3–4 demo clients via Flyway: one "clean," one with a large outflow + maturing CD (the hero demo), one dormant. `last_meeting_date` should be ~30–60 days back so deltas are meaningful.

## 8. API surface (brief-api)

- `GET  /clients` — list seeded clients (id, name, segment) for the UI dropdown.
- `POST /briefs/{clientId}` — kick off brief generation; returns a job/stream id.
- `GET  /briefs/{clientId}/stream` — SSE stream of agent steps + final brief JSON.
- `POST /admin/seed-client` — (dev) create client + Plaid item + CRM rows + optional planted transactions.
- `GET  /health` — health check for compose.

Final brief response schema (the synthesis call must return exactly this):

```json
{
  "clientId": "string",
  "snapshot": { "name": "", "segment": "", "tenureYears": 0, "totalRelationshipValue": 0 },
  "changedSinceLastMeeting": [ { "summary": "", "citations": ["txn_4521"] } ],
  "watchOuts": [ { "summary": "", "severity": "high|med|low", "citations": [] } ],
  "opportunities": [ { "summary": "", "citations": [] } ],
  "talkingPoints": [ "", "", "" ],
  "lastInteractionRecap": { "date": "", "summary": "", "openPromises": [] }
}
```

## 9. Docker Compose layout

```yaml
services:
  brief-api:
    build: ./brief-api
    env_file: .env            # ANTHROPIC_API_KEY, PLAID_CLIENT_ID, PLAID_SECRET, PLAID_ENV
    environment:
      SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/rmbrief
      SPRING_DATA_REDIS_HOST: redis
    ports: ["8080:8080"]
    depends_on: [postgres, redis]
  postgres:
    image: postgres:16
    environment:
      POSTGRES_DB: rmbrief
      POSTGRES_USER: rm
      POSTGRES_PASSWORD: rm
    ports: ["5432:5432"]
    volumes: ["pgdata:/var/lib/postgresql/data"]
  redis:                      # optional cache
    image: redis:7
    ports: ["6379:6379"]
volumes: { pgdata: {} }
```

- **Outbound calls allowed:** only `sandbox.plaid.com` and `api.anthropic.com`. Everything else is container-to-container.
- Multi-stage Dockerfile for `brief-api` (Maven build stage → slim JRE runtime stage).
- Secrets via `.env` (gitignored); provide `.env.example`.

## 10. Suggested project structure

```
rm-brief/
├── docker-compose.yml
├── .env.example
├── brief-api/
│   ├── Dockerfile
│   ├── pom.xml
│   └── src/main/java/com/demo/rmbrief/
│       ├── RmBriefApplication.java
│       ├── agent/        # Orchestrator, tool registry, step streamer
│       ├── tools/        # getAccounts, getTransactions, ... (each wraps Plaid or CRM)
│       ├── plaid/        # PlaidClient config + bootstrap/seed helpers
│       ├── crm/          # JPA entities, repositories
│       ├── delta/        # deterministic ChangeReport computation
│       ├── llm/          # Anthropic client + synthesis prompt + JSON parsing
│       ├── web/          # controllers, SSE
│       └── config/
│   └── src/main/resources/
│       ├── application.yml
│       └── db/migration/ # Flyway V1__schema.sql, V2__seed.sql
└── ui/                   # static page (or fold into brief-api static resources)
```

## 11. Build order (for Claude Code)

1. Scaffold Spring Boot project + Maven + Dockerfile + docker-compose with Postgres/Redis, health check, Flyway schema. **Verify it boots in compose.**
2. CRM layer: entities, repos, Flyway seed of 3 demo clients (incl. the hero client). `GET /clients` working.
3. Plaid integration: client config, bootstrap (public_token→exchange), `/admin/seed-client` that attaches a Plaid item per client and (optionally) plants a large transaction. Persist access_tokens.
4. Tools: implement each read tool returning structured JSON. Unit-test against sandbox.
5. Delta engine: deterministic `ChangeReport` from current data + `last_meeting_date`.
6. Agent orchestrator: tool-calling loop with Claude, step streaming over SSE.
7. Synthesis: final Claude call returning the strict brief JSON; parse + serve.
8. Thin UI: client dropdown → trigger → render streamed steps + final brief with citations.
9. Polish: seed the hero scenario end-to-end so a single click produces an impressive brief.

## 12. Demo script (the win)

Pick the hero client. RM selects them → agent visibly fetches accounts, transactions, holdings, CRM notes → flags the $50k outflow and the CD maturing in 3 weeks → produces 3 talking points, each citing a transaction or account. Emphasize: multi-step reasoning, grounded citations, "I don't have data on X" honesty, and that it's all read-only + local + standards-portable (FDX normalization noted as a production next step).

## 13. Notes / guardrails for the implementer

- Keep deltas/risk thresholds in `application.yml` (configurable).
- The LLM never invents numbers — it writes *from* the structured data + ChangeReport only; enforce via the synthesis prompt and reject briefs whose citations don't resolve to real ids.
- Log every tool call + Claude request/response at debug for the demo narrative.
- No real PII; all data is sandbox/synthetic.
- Don't add write-capable Plaid scopes even if convenient.

# Architecture

Four diagrams: the system as a whole, then the three flows (pre-meeting brief, brief chat,
post-meeting capture). Code lives in [`brief-api/`](../brief-api/); package names below are under
`com.demo.rmbrief`.

The rule throughout: **Java decides what is true, Claude decides how to say it.** Numbers, dates,
comparisons and citation checks are code; the model writes prose, extracts structure and picks
tools.

## 1. System overview

```mermaid
flowchart LR
    RM(["Relationship Manager<br/>(browser)"])

    subgraph app["brief-api (Spring Boot)"]
        UI["Static UI<br/>index.html · capture.html"]
        BRIEF["Brief pipeline<br/>agent · delta · llm"]
        CHAT["Brief chat<br/>chat"]
        CAP["Meeting capture<br/>capture"]
        AC["AnthropicClient<br/>(one shared client)"]
    end

    PG[("PostgreSQL<br/>CRM · briefs · captures · audit")]
    TEMP["Temporal<br/>durable workflows"]
    WH["Whisper<br/>speech-to-text"]
    MEDIA[("Encrypted media<br/>AES-256-GCM")]
    PLAID["Plaid Sandbox<br/>read-only"]
    CLAUDE["Claude API<br/>direct or via gateway"]

    RM --> UI
    UI --> BRIEF & CHAT & CAP
    BRIEF --> PG & PLAID
    CHAT --> PG & PLAID
    CAP --> PG & MEDIA & WH
    CAP <--> TEMP
    BRIEF & CHAT & CAP --> AC --> CLAUDE
```

Redis is wired but off by default. All containers run from `docker-compose.yml`.

## 2. Pre-meeting brief

One HTTP request, streamed as Server-Sent Events.

```mermaid
sequenceDiagram
    participant RM as RM (browser)
    participant API as BriefController / AgentOrchestrator
    participant Data as CRM (Postgres) + Plaid
    participant Delta as DeltaService (Java)
    participant LLM as Claude (BRIEF_MODEL)
    participant DB as BriefStore

    RM->>API: GET /briefs/{clientId}/stream
    API->>Data: 6 read-only tools (CRM, accounts, transactions, holdings, liabilities, identity)
    Data-->>API: raw data
    API-->>RM: SSE tool steps
    API->>Delta: compute ChangeReport
    Note over Delta: balances, large outflows, maturity alerts,<br/>dormancy, promise due status — zero tokens
    Delta-->>API: ChangeReport
    API->>LLM: one call: ChangeReport + CRM context (compact JSON)
    LLM-->>API: brief JSON with citations
    API->>API: validateCitations — strip IDs not in ChangeReport
    API->>DB: save as version 1
    API-->>RM: SSE brief + brief_saved (briefId)
```

## 3. Brief chat

Stateless on the server: the browser sends the text history with each question; nothing is stored.

```mermaid
sequenceDiagram
    participant RM as RM (browser)
    participant Chat as ChatService
    participant LLM as Claude (CHAT_MODEL)
    participant Tools as ChatTools (Java, read-only)
    participant Store as BriefStore

    RM->>Chat: POST /briefs/saved/{id}/chat {history, message}
    Chat->>LLM: cached instructions + brief context + tools, then history + question
    loop up to 8 rounds
        LLM-->>Chat: tool call (stream)
        Chat->>Tools: search / summarize / compare transactions,<br/>accounts, holdings, CRM notes & promises
        Note over Tools: every total and difference computed in Java
        Tools-->>Chat: result + citable IDs
        Chat->>LLM: tool result
    end
    LLM-->>Chat: answer with [[id]] citations (streamed)
    Chat->>Chat: strip [[id]]s no tool returned
    Chat-->>RM: SSE step · delta · proposal · answer
    opt RM clicks Apply on a proposed edit
        RM->>Store: POST /briefs/saved/{id}/edits {baseVersion, edit}
        Store->>Store: re-check citations against live data,<br/>reject if the brief changed (409)
        Store-->>RM: new version n+1
    end
```

## 4. Post-meeting capture

One Temporal workflow per capture. Only the capture ID crosses into Temporal; notes, transcripts
and media stay in Postgres and the encrypted media store.

```mermaid
stateDiagram-v2
    [*] --> RECEIVED: POST /captures<br/>(recording needs policy ON + consent)
    RECEIVED --> TRANSCRIBING
    TRANSCRIBING --> LABELLING_SPEAKERS: meeting recording
    TRANSCRIBING --> EXTRACTING: notes / photos / voice memo
    LABELLING_SPEAKERS --> EXTRACTING
    EXTRACTING --> RESOLVING: one Claude call (CAPTURE_MODEL)
    RESOLVING --> READY_FOR_REVIEW: Java resolves dates and products,<br/>flags items that need a decision
    READY_FOR_REVIEW --> READY_FOR_REVIEW: reminder after 48h
    READY_FOR_REVIEW --> COMMITTED: approve signal
    READY_FOR_REVIEW --> REJECTED: reject signal
    COMMITTED --> [*]: raw media deleted after retention
    REJECTED --> [*]: raw media deleted after retention
    TRANSCRIBING --> FAILED: non-retryable error
    EXTRACTING --> FAILED
    FAILED --> [*]
    READY_FOR_REVIEW --> PURGED: withdraw-consent signal
    COMMITTED --> PURGED: withdraw-consent signal
    PURGED --> [*]
```

What each step does:

| Step | Who | What |
|---|---|---|
| Transcribing | Whisper / Claude vision | Typed notes split into lines; audio transcribed with timestamps; photos read line by line |
| Labelling speakers | Claude | Recordings only: each line labelled RM or client, marked as inferred |
| Extracting | Claude (structured output) | Summary, promises, life events, product interest, concerns, next meeting — each citing source lines; dates and products copied verbatim |
| Resolving | Java | "next Friday" → a date relative to the meeting; "the CD" → the client's actual product; flags (block approval) vs notes (informational) |
| Review | RM | Edit, Keep/Drop flagged items, approve — nothing reaches the CRM otherwise |
| Commit | Java | Writes the CRM interaction and tracked promises; the next brief shows them as due/overdue |
| Retention | Temporal timer | Deletes raw photos/audio after 30 days (7 for recordings) |

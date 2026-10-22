# RM Pre-Meeting Intelligence Brief — Problem & Solution

**Team focus areas**
- **Primary:** Deliver High-Value Use Cases (Embedded Banking / Finance)
- **Secondary:** Advance Agent-Based AI Capabilities · Operationalize GenAI (LLMOps & Quality)

---

## The problem

A Relationship Manager (RM) walks into 6–8 client meetings a day. Before each one, they
manually stitch together a picture of the client from four or five disconnected places:
the core banking screens, the CRM notes from the last meeting, recent transactions, product
holdings, and whatever they remember promising last time. This prep is **slow, inconsistent,
and easy to get wrong** — a missed CD maturity or an unexplained large outflow is a missed
conversation, and sometimes a lost relationship.

The obvious instinct — "just ask an LLM to summarize the client" — fails in a bank for three
reasons:

1. **Hallucinated numbers are unacceptable.** An RM cannot quote a balance or a maturity date
   that the model invented. One wrong figure in front of a client destroys trust in the tool.
2. **Cost and latency at scale.** Thousands of RMs × thousands of meetings means a chatty,
   multi-call, frontier-model-for-everything design is too expensive and too slow to run.
3. **Auditability.** Every claim has to trace back to a real source record, or compliance
   will not let it near a customer conversation.

## Who it's for

Front-line **Relationship Managers** and their team leads. The brief is the thing they read
in the 90 seconds before a client walks in.

## Our solution

An **agentic service that produces a one-page, fully-cited pre-meeting brief from a single
client ID in under a minute.** The RM picks a client; the system gathers data from the bank's
systems, computes what actually changed since the last meeting, and writes a grounded brief:

- **Snapshot** — name, segment, tenure, total relationship value
- **What changed since last meeting** — notable transactions and balance moves
- **Watch-outs** — risk flags (large outflow, low balance, dormancy) with severity
- **Opportunities** — maturing instruments, underused products, cross-sell
- **3 talking points** — concrete, data-grounded conversation starters
- **Last interaction recap** — what was discussed and promised last time

**Every claim carries a citation** (transaction ID or account ID) that the RM can click to see
the exact underlying record.

### The design decision that makes it bank-grade

We split the work into **"what is true" (deterministic) and "how to say it" (the LLM)** —
the model is never trusted with arithmetic, dates, or comparisons.

```
Client ID
   │
   ├─ 1. GATHER    6 read-only tools → Plaid Sandbox + synthetic CRM (Postgres)
   │
   ├─ 2. DELTA     Pure Java. Computes the ChangeReport: balance moves, large
   │               outflows, maturity alerts, dormancy. ZERO LLM tokens.
   │
   ├─ 3. SYNTHESIZE  ONE Claude call. Writes prose strictly from the ChangeReport.
   │                 Forbidden from referencing any number not in the input.
   │
   └─ 4. VALIDATE  Every citation in the output is checked against the real IDs in
                   the ChangeReport. Unresolvable citations are stripped before the
                   brief is ever shown. The UI flags any that don't resolve.
```

This is what lets us promise the three things a bank needs: **no invented numbers** (the
math is code, the prompt is constrained), **auditability** (clickable citations validated in
code), and **cost control** (one right-sized model call per brief, not a reasoning loop).

### Closing the loop: post-meeting capture

The brief *reads* from the CRM; capture *writes* to it. After a meeting the RM adds typed notes,
photos of handwritten notes, a 30-second voice memo, or — only when compliance has switched it
on and the client has consented — a recording of the meeting. A durable **Temporal** workflow
per capture transcribes it (self-hosted Whisper; Claude vision for photos), makes one Claude
call to extract a summary, promises, life events, product interest, concerns and the next
meeting, then resolves "next Friday" and "the CD" into real dates and accounts **in Java**.
Every drafted item cites the source line it came from. The RM reviews, edits and approves —
**nothing reaches the CRM without that approval** — and approved promises are tracked, so the
next brief flags them as due or overdue. Photos and audio are encrypted at rest and deleted after
a retention period; withdrawing consent purges a recording and everything derived from it.

### Refining the brief in chat

Once a brief exists, the RM can ask about it ("how much went to United Airlines, by month?") or
ask for changes ("add a watch-out about the duplicate promises"). Claude answers through
read-only tools scoped to that one client; **every total and comparison is computed by a Java
tool**, and every fact carries a validated citation. Changes are only *proposed*: the RM sees
before/after and clicks **Apply**, which saves a new brief version (earlier versions stay
viewable). Chat is not stored, declines investment/tax/legal advice, and won't draft client
emails.

### Why it's "agentic"

The orchestrator runs a real tool-calling loop: given the client ID and a tool catalog,
Claude can plan and sequence the read tools itself (`getCrmContext`, `getAccounts`,
`getTransactions`, `getHoldings`, `getLiabilities`, `getIdentity`), and the intermediate
steps stream live to the UI so the reasoning is observable. For cost predictability the
default runtime path calls the tools eagerly (deterministic, zero planning tokens); the
agentic planning mode is the same code path with one config flag. We demo both.

Chat is a second agent: for each question Claude decides which tools to call (search or total
transactions, compare periods, read CRM notes and promises, propose an edit), streams its answer,
and shows each step as it works. Capture is a third, long-running one: a Temporal workflow that
survives restarts, waits days for the RM's review, and runs retention timers.

## Why this advances M&T

- **Directly reusable pattern.** "Deterministic facts + constrained LLM prose + validated
  citations" is a template that applies to any customer-facing GenAI surface where wrong
  numbers are unacceptable — not just RM briefs.
- **Standards-portable.** Today it reads Plaid Sandbox; the same tool interface maps onto
  FDX-normalized internal data with no change to the delta or synthesis layers.
- **Built to run at bank scale.** The token-efficiency posture (below) is a first-class
  design goal, not an afterthought.

## Token efficiency (shared submission constraint)

This project treats token cost as a design constraint, not a detail:

- **All numeric reasoning is deterministic Java — it costs zero tokens.** Balance deltas,
  threshold checks, maturity countdowns, and dormancy are computed in code.
- **One LLM call per brief.** The synthesis step is a single, non-streaming call. There is no
  multi-turn tool loop in the default brief path.
- **Right-sized, configurable models.** Every feature defaults to **Claude Sonnet 5** — strong
  enough for grounded prose, extraction and tool use, far cheaper than a frontier model. Each
  feature's model is one setting in `application.yml`; because correctness lives in code, a
  smaller model can be swapped in where quality allows.
- **Lean inputs.** The brief receives only the structured `ChangeReport` + CRM context as
  compact JSON, not raw transaction dumps — the delta step pre-filters to what matters.
- **Chat reuses context.** Instructions, tools and the brief are prompt-cached, so follow-up
  questions mostly read from cache (~1¢ each). Totals come from Java tools, not model reasoning.
- **Capture spends tokens only on language.** Speech-to-text is self-hosted Whisper; dates and
  products are resolved in Java; one extraction call per capture.
- **Measured, not guessed.** Every Claude call logs its model and token counts; the CBA uses
  those measurements (~4¢ per brief).

See [`/cba`](../cba/) for the cost-benefit numbers and [`docs/model-usage.md`](model-usage.md)
for the model breakdown.

## AI tools used (and why)

- **Claude Code (Opus 4.8 / Sonnet 4.6 for Phase 1; Opus 5.5 for capture and chat)** — used to
  design and build the entire codebase (planning + code generation). Chosen for strong
  Java/Spring reasoning and agentic tool use.
- **Claude Sonnet 5 via the Anthropic Messages API** — the default runtime model for brief
  synthesis, chat and capture extraction/vision. Chosen as the right-sized model; configurable
  per feature.
- **Whisper (self-hosted)** — speech-to-text for voice memos and recordings, so audio stays on
  the bank's network.

> AI assistance disclosure: this repository (code, this document, the CBA, and the README)
> was produced with Claude Code. All financial data is **synthetic / Plaid Sandbox** — no
> customer PII, account data, or confidential business data was used at any point.

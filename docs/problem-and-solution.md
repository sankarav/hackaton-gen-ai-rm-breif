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
client ID in seconds.** The RM picks a client; the system gathers data from the bank's
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

### Why it's "agentic"

The orchestrator runs a real tool-calling loop: given the client ID and a tool catalog,
Claude can plan and sequence the read tools itself (`getCrmContext`, `getAccounts`,
`getTransactions`, `getHoldings`, `getLiabilities`, `getIdentity`), and the intermediate
steps stream live to the UI so the reasoning is observable. For cost predictability the
default runtime path calls the tools eagerly (deterministic, zero planning tokens); the
agentic planning mode is the same code path with one config flag. We demo both.

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
  multi-turn "think out loud" loop in the default runtime path.
- **Right-sized model.** We use **Claude Sonnet 4.6** for synthesis — strong enough for
  grounded financial prose, far cheaper than a frontier model, and reserved only for the one
  step that genuinely needs language generation. The deterministic layer would let us drop to
  an even smaller model with no loss of correctness.
- **Lean inputs.** The model receives only the structured `ChangeReport` + CRM context, not
  raw transaction dumps — the delta step pre-filters to what matters.

See [`/cba`](../cba/) for the cost-benefit numbers and [`docs/model-usage.md`](model-usage.md)
for the model breakdown.

## AI tools used (and why)

- **Claude Code (Opus 4.8 / Sonnet 4.6)** — used to design and build the entire codebase
  (planning + code generation). Chosen for strong Java/Spring reasoning and agentic tool use.
- **Claude Sonnet 4.6 via the Anthropic Messages API** — the runtime synthesis model inside
  the product. Chosen as the right-sized model for grounded prose generation.

> AI assistance disclosure: this repository (code, this document, the CBA, and the README)
> was produced with Claude Code. All financial data is **synthetic / Plaid Sandbox** — no
> customer PII, account data, or confidential business data was used at any point.

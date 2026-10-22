# Cost-Benefit Analysis — RM Pre-Meeting Intelligence Brief

> Back-of-the-envelope. Precision is not the goal — the assumptions are stated so a
> reviewer can swap in M&T's real numbers. All figures are illustrative and use
> synthetic/sandbox data only. Per-use token counts below were **measured** from the app's
> `[llm]` usage logs while testing.

## Summary

| | Per meeting | Per RM / year | 50-RM pilot / year |
|---|---|---|---|
| **LLM cost** (brief + chat + capture) | ~$0.08 | ~$100 | ~$5,000 |
| **RM prep time reclaimed** | ~10 min | ~220 hrs | ~11,000 hrs |
| **Value of time reclaimed** | ~$12.50 | ~$16,500 | ~$825,000 |
| **Net value** | ~$12.42 | ~$16,400 | ~$820,000 |

The headline: **the brief itself costs ~4¢ to produce, against ~$12.50 of RM time saved —
roughly 300×.** Adding chat and post-meeting capture on every meeting roughly doubles the model
cost to ~8¢, and the return is still **~150×**. That ratio holds because of the architecture, not
in spite of it (see *Why the economics are structural*).

---

## Assumptions

| Input | Value | Basis |
|---|---|---|
| Manual prep time per meeting | 10 min | Pulling core-banking screens, CRM notes, recent transactions, and recalling last meeting — conservative; many RMs spend more. |
| RM fully-loaded cost | $75 / hr | Mid-range loaded cost for a relationship manager. Swap in M&T's actual figure. |
| Meetings per RM per day | 6 | Front-line RM cadence. |
| Working days per year | 220 | Net of leave/holidays. |
| Briefs per RM per year | ~1,320 | 6 × 220. |
| Chat usage | 2 questions on half of briefs | Illustrative; chat is optional. |
| Captures per RM per year | ~1,320 | One per meeting (typed notes or a voice memo). |
| Pilot size | 50 RMs | Illustrative first-wave rollout. |
| Model | Claude Sonnet 5: $2 / 1M input, $10 / 1M output | Default for every feature; configurable. |

---

## Ongoing cost — per use (measured)

All data gathering, delta computation, date/product resolution and chat arithmetic are
deterministic Java and **cost zero tokens**. Only the steps that need language cost anything.

### Pre-meeting brief — one Claude call

| Component | Tokens | Cost |
|---|---|---|
| Input (ChangeReport + CRM context, compact JSON) | ~3,100 | $0.006 |
| Output (brief JSON, including the model's thinking) | ~3,000 | $0.030 |
| **Total per brief** | | **~$0.04** |

### Brief chat — per question

| | Cost | Why |
|---|---|---|
| First question in a conversation | ~$0.02 | Writes ~5,800 tokens of instructions, tools and brief context to the prompt cache |
| Each follow-up question | ~$0.01 | Reads that context from cache at 10% of the input price |

### Meeting capture — per capture

| Component | Tokens (in / out) | Cost |
|---|---|---|
| Extraction (summary, promises, signals) | ~1,700–2,900 / ~550–1,700 | $0.01–$0.02 |
| Each photo of handwritten notes | ~2,100 / ~300 | ~$0.007 |
| Speaker labelling (meeting recordings only) | ~1,300 / ~900 | ~$0.012 |
| Speech-to-text | — | $0 marginal (self-hosted Whisper) |
| **Typical capture** (notes or a voice memo) | | **~$0.02** |

### Per RM per year

| | Calculation | Cost |
|---|---|---|
| Briefs | 1,320 × $0.04 | ~$53 |
| Chat | 660 briefs × ($0.02 + $0.01) | ~$20 |
| Capture | 1,320 × $0.02 | ~$26 |
| **Total** | | **~$100** |

**Infrastructure** for a pilot: five containers (Spring Boot app, Postgres, Redis, Temporal,
Whisper) on a small host or cloud footprint — order **$400–$1,000/month**, independent of usage.
Whisper is the heaviest; on CPU it transcribes a short memo in about a second. A larger pilot
might move speech-to-text to a GPU or a sanctioned hosted service (a config switch).

---

## One-time implementation cost

The POC is working end-to-end. To harden it into a pilot-ready MVP:

| Work item | Estimate |
|---|---|
| Replace Plaid Sandbox with FDX-normalized internal data behind the same tool interface | 3 weeks |
| AuthN/AuthZ (real RM identity instead of the demo actor), secrets management | 2 weeks |
| Productionize SSE/UI, error handling, observability (token usage as metrics) | 2 weeks |
| Temporal production cluster (or Temporal Cloud with payload encryption) | 1 week |
| Speech-to-text procurement / hosting; compliance sign-off on recording, consent and retention | 1 week |
| Pilot integration + UAT with a small RM cohort | 1 week |
| **Total** | **~10 weeks, 2 engineers ≈ $58,000** |

(At a fully-loaded engineering cost of ~$2,900/engineer/week.) Already done in the POC and
not costed above: the corporate AI-gateway routing, configurable models, audit trail, encrypted
media storage and consent-withdrawal purge.

---

## Value — what the RM gets back

**10 minutes of focused prep per meeting, reclaimed.** At 1,320 meetings/yr that is
**~220 hours/RM/yr**, or **~$16,500/RM/yr** of time at a $75/hr loaded cost. Across a 50-RM
pilot: **~11,000 hours and ~$825,000/yr.**

This is **capacity, not just cost savings** — the reclaimed time goes back into client-facing
work. Three further benefits are real but deliberately **not** included in the headline:

- **Post-meeting admin.** Capture turns notes, photos or a 30-second voice memo into a reviewed
  CRM entry. If it saves even 5 minutes of note-writing per meeting, that is another ~110
  hours/RM/yr.
- **Promises kept.** Commitments made in a meeting become tracked items that show up as due or
  overdue in the next brief — fewer dropped follow-ups.
- **Revenue signals.** Catching a CD maturity or a large outflow before the client does.

---

## Payback

- **Break-even on the ~$58k build:** ~4 RMs for one year, or the full 50-RM pilot in
  **under one month** of reclaimed time.
- **Run cost vs. value:** at pilot scale, ongoing cost (~$5,000 LLM + ~$5k–$12k infra) is
  **1–2%** of the time value it returns.

---

## Key risks

| Risk | Mitigation |
|---|---|
| **"Time saved" is soft** — reclaimed minutes don't automatically convert to value. | Frame as capacity; pair the pilot with a simple before/after measure of prep time and meeting throughput. |
| **Data integration is the real work** — Plaid Sandbox ≠ M&T's core systems. | The tool interface is the seam: the delta, synthesis and chat layers don't change when the data source becomes FDX-normalized internal data. Integration cost is in the one-time estimate. |
| **LLM hallucination in a client-facing artifact.** | Mitigated by design: deterministic deltas and chat arithmetic, constrained prompts, and citation validation that strips any unresolvable ID. Capture and chat changes need RM approval before they take effect. |
| **Recording client meetings.** | Off by default behind a compliance-owned switch; requires captured consent; recordings have a stricter retention period; consent withdrawal purges the recording and everything derived from it. |
| **Sending data to an external AI service.** | POC uses only synthetic/sandbox data. Production routes through M&T's sanctioned gateway (already supported via the `gateway` profile); speech-to-text runs self-hosted by default. |
| **Cost scaling with adoption.** | Cost is linear and small (~8¢ per meeting with every feature used), and each feature's model is a config setting, so a cheaper model can be used where quality allows. |

---

## Why the economics are structural

A naive "summarize this client with an LLM" design would make several chatty frontier-model
calls per brief and still risk wrong numbers. This design makes **one right-sized call per
brief** and does all arithmetic in code — in chat too, where totals and comparisons come from
Java tools — so the cost is a few cents **and** every figure is auditable. The cost advantage
and the correctness guarantee come from the same architectural decision. See
[`docs/model-usage.md`](../docs/model-usage.md) and
[`docs/problem-and-solution.md`](../docs/problem-and-solution.md).

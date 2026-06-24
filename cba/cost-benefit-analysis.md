# Cost-Benefit Analysis — RM Pre-Meeting Intelligence Brief

> Back-of-the-envelope. Precision is not the goal — the assumptions are stated so a
> reviewer can swap in M&T's real numbers. All figures are illustrative and use
> synthetic/sandbox data only.

## Summary

| | Per brief | Per RM / year | 50-RM pilot / year |
|---|---|---|---|
| **LLM cost** | ~$0.04 | ~$53 | ~$2,600 |
| **RM time reclaimed** | ~10 min | ~220 hrs | ~11,000 hrs |
| **Value of time reclaimed** | ~$12.50 | ~$16,500 | ~$825,000 |
| **Net value** | ~$12.46 | ~$16,400 | ~$820,000 |

The headline: **the LLM cost to produce a brief (~4¢) is roughly 300× smaller than the
value of the RM time it saves (~$12.50).** That ratio is the whole case, and it holds
because of the architecture, not in spite of it (see Token Efficiency).

---

## Assumptions

| Input | Value | Basis |
|---|---|---|
| Manual prep time per meeting | 10 min | Pulling core-banking screens, CRM notes, recent transactions, and recalling last meeting — conservative; many RMs spend more. |
| RM fully-loaded cost | $75 / hr | Mid-range loaded cost for a relationship manager. Swap in M&T's actual figure. |
| Meetings per RM per day | 6 | Front-line RM cadence. |
| Working days per year | 220 | Net of leave/holidays. |
| Briefs per RM per year | ~1,320 | 6 × 220. |
| Pilot size | 50 RMs | Illustrative first-wave rollout. |

---

## Ongoing cost — per brief

The runtime cost of a brief is **one Claude Sonnet 4.6 call** (synthesis). Everything else —
the six data-gather tools and the entire delta computation — is deterministic Java and
**costs zero tokens**.

| Component | Tokens | Rate (Sonnet 4.6) | Cost |
|---|---|---|---|
| Synthesis input (ChangeReport + CRM JSON) | ~5,000 | $3.00 / 1M | $0.015 |
| Synthesis output (brief JSON) | ~1,500 | $15.00 / 1M | $0.0225 |
| **Total per brief** | | | **~$0.04** |

> Rounded to **$0.05/brief** in the summary table to absorb prompt-size variance and the
> occasional LLM-driven gather run. At 1,320 briefs/RM/yr that is **~$53/RM/yr** in model cost.

**Infrastructure** for a pilot is modest — three small containers (Spring Boot app, Postgres,
Redis) on a single host or small cloud footprint: order **$200–$500/month** for the pilot,
independent of brief volume.

---

## One-time implementation cost

The hackathon POC is already working end-to-end. To harden it into a pilot-ready MVP:

| Work item | Estimate |
|---|---|
| Replace Plaid Sandbox with FDX-normalized internal data behind the same tool interface | 3 weeks |
| AuthN/AuthZ, audit logging, secrets management | 2 weeks |
| Productionize SSE/UI, error handling, observability | 2 weeks |
| Pilot integration + UAT with a small RM cohort | 1 week |
| **Total** | **~8 weeks, 2 engineers ≈ $50,000** |

(At a fully-loaded engineering cost of ~$2,900/engineer/week.)

---

## Value — what the RM gets back

**10 minutes of focused prep per meeting, reclaimed.** At 1,320 meetings/yr that is
**~220 hours/RM/yr**, or **~$16,500/RM/yr** of time at a $75/hr loaded cost. Across a 50-RM
pilot: **~11,000 hours and ~$825,000/yr.**

This is **capacity, not just cost savings** — the reclaimed time goes back into client-facing
work: more meetings, better-prepared conversations, faster follow-up on the watch-outs and
opportunities the brief surfaces. There is a second-order revenue effect (catching a CD
maturity or a large outflow before the client does) that we deliberately do **not** quantify
here, but it is the more strategic upside.

---

## Payback

- **Break-even on the ~$50k build:** ~3 RMs for one year, or the full 50-RM pilot in
  **under one month** of reclaimed time.
- **Run cost vs. value:** at the pilot scale, ongoing cost (~$2,600 LLM + ~$6k infra) is
  **under 1%** of the time value it returns.

---

## Key risks

| Risk | Mitigation |
|---|---|
| **"Time saved" is soft** — reclaimed minutes don't automatically convert to value. | Frame as capacity; pair the pilot with a simple before/after measure of prep time and meeting throughput. |
| **Data integration is the real work** — Plaid Sandbox ≠ M&T's core systems. | The tool interface is the seam: the delta and synthesis layers don't change when the data source becomes FDX-normalized internal data. Integration cost is in the one-time estimate. |
| **LLM hallucination in a client-facing artifact.** | Already mitigated by design: deterministic deltas, a no-invented-numbers prompt, and citation validation that strips any unresolvable ID before the brief is shown. This is the architecture's core guarantee, not a future task. |
| **Sending data to an external AI service.** | POC uses only synthetic/sandbox data. For production, route through M&T's sanctioned model gateway; no customer PII leaves the boundary. |
| **Cost scaling with adoption.** | Cost is linear and tiny (~5¢/brief) and the deterministic layer lets us drop to a smaller/cheaper model with no loss of correctness if volume demands. |

---

## Why the economics are structural

A naive "summarize this client with an LLM" design would make several chatty frontier-model
calls per brief and still risk wrong numbers. This design makes **one right-sized call** and
does all arithmetic in code — so the per-brief cost is ~4¢ **and** auditable. The cost
advantage and the correctness guarantee come from the same architectural decision. See
[`docs/model-usage.md`](../docs/model-usage.md) and
[`docs/problem-and-solution.md`](../docs/problem-and-solution.md).

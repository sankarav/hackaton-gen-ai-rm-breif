# Model Usage

This submission uses Claude models in two distinct roles. Per the submission guidelines,
they are listed below split by **planning vs. execution** and **code vs. non-code**.

## At a glance

| Role | Surface | Model | Why this model |
|---|---|---|---|
| **Planning** (non-code) | Architecture, build sequencing, docs, CBA | Claude Code — Claude 4.x family (**Opus 4.8** / **Sonnet 4.6**) | Strongest reasoning for system design and writing; ran the agentic build loop. |
| **Execution** (code) | Generating the Java/Spring codebase + UI | Claude Code — **Sonnet 4.6** (most of the build), **Opus 4.8** (final hardening) | Strong Java/Spring code generation and tool use. |
| **Execution** (runtime, in-product) | Brief synthesis inside the running app | **Claude Sonnet 4.6** (`claude-sonnet-4-6`) | Right-sized: strong enough for grounded financial prose, far cheaper than a frontier model, reserved for the one step that needs language generation. |

## Planning / non-code

- **Tool:** Claude Code (Anthropic's official CLI), Claude 4.x family.
- **Used for:** decomposing the spec into the numbered build steps, choosing the
  deterministic-delta + constrained-synthesis architecture, the SDK-discovery work for
  `plaid-java` and `anthropic-java`, and authoring this repo's documentation, the
  problem/solution write-up, and the cost-benefit analysis.
- **AI-assistance disclosure:** the code, README, `docs/`, and `cba/` in this repository were
  produced with Claude Code.

## Execution / code

- **Tool:** Claude Code.
- **Models:** **Sonnet 4.6** for the bulk of code generation (cost-efficient, strong on
  Java/Spring), with **Opus 4.8** used for the final review/hardening pass.
- **Used for:** generating the Spring Boot services, Plaid/Anthropic SDK wiring, the
  deterministic `DeltaService`, the SSE layer, and the static UI.

## Execution / runtime (the model inside the product)

- **Model:** `claude-sonnet-4-6` via the Anthropic Messages API (`anthropic-java` 2.41.0).
- **Where:** `SynthesisService` (the single final synthesis call) and, when
  `app.agent.eager-tool-calls=false`, the `AgentOrchestrator` tool-planning loop.
- **What it is NOT used for:** any numeric reasoning. All balance deltas, threshold checks,
  maturity countdowns, and dormancy logic are deterministic Java in `DeltaService` and cost
  zero tokens. The model only writes prose from the computed `ChangeReport`.
- **Right-sizing rationale:** Sonnet 4.6 ($3 / $15 per 1M input/output tokens) is the
  cost-appropriate tier for grounded prose generation. The deterministic layer means we could
  drop to an even smaller model (e.g. Haiku) with no loss of correctness if volume demanded —
  the correctness guarantee lives in code, not in model size. See
  [`cba/cost-benefit-analysis.md`](../cba/cost-benefit-analysis.md) for per-brief cost.

## Reproducibility

The runtime model ID, prompts, and configuration are committed:

- Synthesis prompt + model: [`SynthesisService.java`](../brief-api/src/main/java/com/demo/rmbrief/llm/SynthesisService.java) (mirrored in [`/prompts`](../prompts/))
- Agent gather prompt + tool definitions: [`AgentOrchestrator.java`](../brief-api/src/main/java/com/demo/rmbrief/agent/AgentOrchestrator.java) (mirrored in [`/prompts`](../prompts/))
- Thresholds and the eager-vs-agentic flag: [`application.yml`](../brief-api/src/main/resources/application.yml)

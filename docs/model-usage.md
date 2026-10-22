# Model Usage

This project uses Claude models in two roles: to **build** the codebase (Claude Code) and
**inside the running product** (the Anthropic Messages API). Per the submission guidelines, they
are listed below split by **planning vs. execution** and **code vs. non-code**.

## At a glance

| Role | Surface | Model | Why this model |
|---|---|---|---|
| **Planning** (non-code) | Architecture, build sequencing, docs, CBA | Claude Code — Claude 4.x family (**Opus 4.8** / **Sonnet 4.6**) for Phase 1; **Opus 5.5** for Phase 2 (capture) and Phase 3 (chat) | Strongest reasoning for system design and writing; ran the agentic build loop. |
| **Execution** (code) | Generating the Java/Spring codebase + UI | Claude Code — **Sonnet 4.6** (most of Phase 1), **Opus 4.8** (Phase 1 hardening), **Opus 5.5** (Phases 2–3) | Strong Java/Spring code generation and tool use. |
| **Execution** (runtime, in-product) | Brief synthesis, brief chat, meeting-capture extraction and vision | **Claude Sonnet 5** (`claude-sonnet-5`) by default — configurable per feature | Right-sized: strong grounded prose and tool use at a fraction of frontier-model cost; correctness comes from the deterministic layer, not model size. |

## Planning / non-code

- **Tool:** Claude Code (Anthropic's official CLI).
- **Used for:** decomposing the spec into build steps; choosing the deterministic-delta +
  constrained-synthesis architecture; designing the Phase 2 capture workflow (Temporal, consent,
  retention) and the Phase 3 chat (tools, versioned edits); SDK discovery for `plaid-java`,
  `anthropic-java` and `temporal-sdk`; and authoring this repository's documentation, the
  problem/solution write-up and the cost-benefit analysis.
- **AI-assistance disclosure:** the code, README, `docs/`, `prompts/` and `cba/` in this
  repository were produced with Claude Code.

## Execution / code

- **Tool:** Claude Code.
- **Models:** **Sonnet 4.6** for the bulk of Phase 1 code generation, **Opus 4.8** for the Phase 1
  review/hardening pass, and **Opus 5.5** for Phases 2 and 3.
- **Used for:** the Spring Boot services, Plaid/Anthropic/Temporal SDK wiring, the deterministic
  `DeltaService`, `DateResolver` and `ProductResolver`, the SSE layers, and the static UI.

## Execution / runtime (the models inside the product)

Every call site reads its model from `application.yml`, overridable from `.env`:

| Feature | Call | Setting (env) | Default | Per use |
|---|---|---|---|---|
| Pre-meeting brief | Synthesis (`SynthesisService`) | `BRIEF_MODEL` | `claude-sonnet-5` | 1 call per brief |
| Pre-meeting brief | Gather planning (`AgentOrchestrator`, only if `eager-tool-calls=false`) | `AGENT_MODEL` | `claude-sonnet-5` | Short tool loop |
| Brief chat | Streaming tool-use loop (`ChatService`) | `CHAT_MODEL` | `claude-sonnet-5` | 1–3 rounds per question |
| Meeting capture | Photo transcription, speaker labelling, extraction (`CaptureLlm`) | `CAPTURE_MODEL` | `claude-sonnet-5` | 1 extraction + 1 per photo + 1 for recordings |
| Meeting capture | Speech-to-text | `TRANSCRIPTION_PROVIDER` | self-hosted Whisper (`base.en`) | Not a Claude call |

- **What the models are NOT used for:** any numeric or date reasoning. Balance deltas, threshold
  checks, maturity countdowns, dormancy and promise due status are computed in `DeltaService`;
  chat totals and comparisons come from Java tools; capture dates ("next Friday") and product
  references ("the CD") are resolved by `DateResolver` / `ProductResolver`. The models write prose,
  extract structure and choose which tool to call.
- **Right-sizing rationale:** Sonnet 5 ($2 / $10 per 1M input/output tokens) is the
  cost-appropriate tier for grounded prose, structured extraction and tool use. Because
  correctness is enforced in code, any feature can move to a smaller or larger model by changing
  one setting.
- **Thinking:** Sonnet 5 thinks before answering by default, and thinking is billed as output.
  That's why the brief's measured output (~3,000 tokens) is larger than the brief itself.
- **Refusal fallback:** capture calls opt into the API's server-side fallback
  (`fallbacks: "default"`), so a refused request is retried on a fallback model instead of failing.
- **Corporate gateway:** all calls share one client; the `gateway` Spring profile routes them
  through a company AI gateway with the key in a custom header (see the README).
- **Cost tracking:** every call logs `[llm] <purpose> model= in= out=`. See
  [`cba/cost-benefit-analysis.md`](../cba/cost-benefit-analysis.md) for measured per-use cost.

## Reproducibility

The runtime prompts, tool schemas and configuration are committed:

- Every prompt and tool schema, mirrored from code: [`/prompts`](../prompts/) (with an
  extraction script that regenerates the mirrors from the compiled classes)
- Models, thresholds, retention timers, the eager-vs-agentic flag and the gateway profile:
  [`application.yml`](../brief-api/src/main/resources/application.yml)

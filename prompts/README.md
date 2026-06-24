# Prompts & Model Configuration

This folder mirrors the exact prompts and model configuration used by the running product so a
reviewer can reproduce results without reading the Java source. The **authoritative** copies
live in code; these are kept in sync:

| Prompt | Source of truth | Mirror |
|---|---|---|
| Synthesis system prompt | `brief-api/.../llm/SynthesisService.java` | [`synthesis-system-prompt.txt`](synthesis-system-prompt.txt) |
| Synthesis user message template | `brief-api/.../llm/SynthesisService.java` | [`synthesis-user-message-template.txt`](synthesis-user-message-template.txt) |
| Agent gather system prompt (LLM mode) | `brief-api/.../agent/AgentOrchestrator.java` | [`agent-gather-system-prompt.txt`](agent-gather-system-prompt.txt) |
| Tool definitions (gather tools) | `brief-api/.../agent/AgentOrchestrator.java` | [`tool-definitions.json`](tool-definitions.json) |

## Model configuration

| Setting | Value |
|---|---|
| Runtime model | `claude-sonnet-4-6` |
| SDK | `anthropic-java` 2.41.0 (`AnthropicOkHttpClient.fromEnv()`, reads `ANTHROPIC_API_KEY`) |
| `max_tokens` | 4096 (synthesis and gather) |
| Sampling | SDK defaults (no temperature/top_p override) |
| Calls per brief | **1** in the default eager-gather mode (synthesis only). The optional LLM-driven gather mode adds one short tool-planning loop. |

## How the prompts enforce the guardrails

- **No invented numbers** — Rule 1 of the synthesis system prompt restricts the model to the
  supplied structured data only.
- **Citations required** — Rules 2–3 require every claim to cite a real `accountId` /
  `transactionId` from the `ChangeReport`.
- **Strict schema** — Rule 4 + the inlined JSON schema force a parseable `BriefSchema` object.
- **Code-side validation** — after the model responds, `SynthesisService.validateCitations()`
  strips any citation ID that does not resolve to the `ChangeReport`, so a hallucinated ID can
  never reach the brief even if the prompt is ignored.

## Token efficiency

All numeric reasoning (deltas, thresholds, maturity countdowns, dormancy) is deterministic
Java and consumes **zero tokens**. The model receives the pre-computed `ChangeReport`, not raw
transaction dumps — keeping the single synthesis call lean. See `docs/model-usage.md` and
`cba/cost-benefit-analysis.md`.

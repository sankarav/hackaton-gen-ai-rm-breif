# Prompts & Model Configuration

This folder mirrors the exact prompts, tool schemas and model configuration used by the running
product, so a reviewer can reproduce results without reading the Java source. The
**authoritative** copies live in code; the `.txt` and chat tool files here were extracted from the
compiled classes, so they match the code byte for byte.

## Prompt inventory

| Feature | Prompt | Source of truth | Mirror |
|---|---|---|---|
| Pre-meeting brief | Synthesis system prompt | `llm/SynthesisService.java` | [`synthesis-system-prompt.txt`](synthesis-system-prompt.txt) |
| Pre-meeting brief | Synthesis user message template | `llm/SynthesisService.java` | [`synthesis-user-message-template.txt`](synthesis-user-message-template.txt) |
| Pre-meeting brief | Gather system prompt (LLM-driven mode only) | `agent/AgentOrchestrator.java` | [`agent-gather-system-prompt.txt`](agent-gather-system-prompt.txt) |
| Pre-meeting brief | Gather tool definitions | `agent/AgentOrchestrator.java` | [`tool-definitions.json`](tool-definitions.json) |
| Meeting capture | Photo-of-notes transcription | `capture/transcribe/ImageTranscriber.java` | [`capture-image-transcription-system-prompt.txt`](capture-image-transcription-system-prompt.txt) |
| Meeting capture | RM / client speaker labelling (recordings) | `capture/extract/SpeakerLabeler.java` | [`capture-speaker-labelling-system-prompt.txt`](capture-speaker-labelling-system-prompt.txt) |
| Meeting capture | Structured CRM extraction | `capture/extract/ExtractionService.java` | [`capture-extraction-system-prompt.txt`](capture-extraction-system-prompt.txt) |
| Brief chat | Chat system instructions | `chat/ChatService.java` | [`chat-system-prompt.txt`](chat-system-prompt.txt) |
| Brief chat | Chat tool definitions | `chat/ChatTools.java` | [`chat-tool-definitions.json`](chat-tool-definitions.json) |

Source paths are relative to `brief-api/src/main/java/com/demo/rmbrief/`.

**Capture output schemas.** The three capture calls use structured outputs: the JSON schema is
derived by the SDK from Java records (`ImageTranscriber.Transcription`, `SpeakerLabeler.Labels`,
`ExtractionService.Output`), so the response always parses into those types.

**Chat context.** After the instructions, the chat system prompt gets a second block with the
client name, the brief JSON (current version, items indexed from 0) and the ChangeReport it was
built from. Both blocks plus the tools are prompt-cached for the conversation.

## Model configuration

Models are set in `application.yml` and can be overridden from `.env`, never in code.

| Call | Setting (env override) | Default | `max_tokens` | Notes |
|---|---|---|---|---|
| Brief synthesis | `app.brief.model` (`BRIEF_MODEL`) | `claude-sonnet-5` | 16,000 | One non-streaming call per brief |
| Gather tool planning | `app.agent.model` (`AGENT_MODEL`) | `claude-sonnet-5` | 16,000 | Only when `eager-tool-calls=false` |
| Brief chat | `app.chat.model` (`CHAT_MODEL`) | `claude-sonnet-5` | 16,000 | Streaming tool-use loop, ≤ 8 rounds per question |
| Capture: photos, speaker labels, extraction | `app.capture.model` (`CAPTURE_MODEL`) | `claude-sonnet-5` | 16,000 | Structured outputs; server-side refusal fallback (`fallbacks: "default"`) |

- **SDK:** `anthropic-java` 2.40.1. All calls share one `AnthropicClient` built in
  `config/AnthropicConfig.java` from `app.anthropic.*` (base URL, key, optional custom key header
  for a corporate gateway).
- **Thinking:** current models think by default and thinking is billed as output, so `max_tokens`
  is set generously (16,000). A response that still hits the limit fails with a clear error
  rather than truncated JSON.
- **Sampling:** SDK defaults (no temperature/top_p override).
- **Speech-to-text is not Claude:** audio goes to a self-hosted Whisper container
  (`TRANSCRIPTION_PROVIDER=local-whisper`), or OpenAI if configured.

## How the prompts enforce the guardrails

- **No invented numbers.** The brief may only use the supplied ChangeReport + CRM context. Chat
  must get every number from a tool result; totals, differences and comparisons are computed by
  Java tools, never by the model.
- **Citations, checked in code.** The brief must cite `accountId`, `transactionId`,
  `productType` or `promiseId` values; `SynthesisService.validateCitations()` strips any ID not in
  the ChangeReport. Chat cites `[[id]]` inline; `ChatService` strips any ID a tool didn't return.
  Capture items cite transcript line numbers; citations to non-existent lines are dropped and the
  item flagged for review.
- **Dates and products resolved in Java.** Capture extraction copies phrases like "next Friday"
  and "the CD" verbatim; `DateResolver` and `ProductResolver` interpret them.
- **Nothing changes without the RM.** Capture drafts need approval before reaching the CRM; chat
  can only *propose* brief edits, applied as a new version when the RM clicks Apply.
- **Boundaries.** Chat declines investment, tax and legal advice and won't draft client emails.
  All prompts treat notes, transcripts and transaction descriptions as data, not instructions.

## Token efficiency

All numeric reasoning (deltas, thresholds, maturity countdowns, dormancy, promise due status,
chat totals and comparisons) is deterministic Java and consumes **zero tokens**. The brief
receives the pre-computed ChangeReport as compact JSON, not raw transaction dumps. Chat caches the
instructions, tools and brief context, so follow-up questions mostly read from cache. Every
Claude call logs `[llm] <purpose> model= in= out=` (chat also logs cache reads/writes). See
[`docs/model-usage.md`](../docs/model-usage.md) and
[`cba/cost-benefit-analysis.md`](../cba/cost-benefit-analysis.md).

## Keeping the mirrors in sync

When a prompt or tool schema changes in code, re-extract the mirrors rather than editing them by
hand. From `brief-api/`:

```bash
mvn -B -q package -DskipTests && mvn -B -q dependency:build-classpath -Dmdep.outputFile=cp.txt
jshell --class-path "target/classes:$(cat cp.txt)" ../prompts/extract-prompts.jsh
```

[`extract-prompts.jsh`](extract-prompts.jsh) covers the capture, chat and synthesis system
prompts and the chat tools. The gather prompt, gather tools and synthesis user-message template
are built inline in code, so update those three files by hand.

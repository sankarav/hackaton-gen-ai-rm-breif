# Known Limitations & Recommended Next Steps

This is a working proof of concept on synthetic data. The limitations below were observed while
building and testing it; the next steps are ordered by how much they matter for a pilot.

## Known limitations

### Data
- **Sandbox and synthetic data only.** Financial data comes from Plaid Sandbox; the CRM is a
  synthetic Postgres schema seeded by Flyway. Nothing has been run against real bank systems.
- **Plaid Sandbox quirks.** Sandbox items expire after a while (`ITEM_LOGIN_REQUIRED`) and must be
  re-seeded; custom "planted" transactions are silently dropped by the sandbox, so scenarios rely
  on the persona's default transactions.
- **Static seed dates.** Seeded meetings and products have fixed 2026 dates, so day counts
  ("matured 106 days ago") drift as the calendar moves.

### Identity and access
- **No authentication.** Every action is attributed to the client's assigned RM as a placeholder
  (`rm:<name>` in the audit trail). There is no per-RM scoping of which clients a user can see.
- **Admin endpoints are open.** The recording-policy toggle and the Plaid seed endpoint have no
  access control.

### Pre-meeting brief
- **Latency.** A brief takes ~30–40 seconds, mostly the model thinking before it writes.
- **Citations prove existence, not support.** Validation strips IDs that don't exist, but cannot
  check that a real ID actually supports the sentence. In testing, a CRM meeting recap was cited
  to an unrelated (real) savings account.
- **CRM meetings aren't citable.** Interactions have no citation ID, and promises from the original
  seed data exist only as text, so they can't be cited or tracked as due/overdue.
- **Briefs are snapshots.** A brief generated before a new capture is approved doesn't reflect it
  until regenerated (chat reads live data and will point out the gap).

### Brief chat
- **Not saved, by design.** Conversations live only in the browser tab; history is capped at
  ~30 messages, and only text carries across turns, so tools re-run on follow-ups.
- **Limited write path.** Chat can propose edits to the four list sections of the brief, but can't
  create tracked promises or edit the snapshot or recap.
- **Up to 8 tool rounds per question.** Very broad questions stop with a note asking for a more
  specific one.

### Meeting capture
- **Speakers are inferred, not detected.** Whisper doesn't separate voices; Claude labels each
  recorded line as RM or client from its content, and every label is flagged as inferred.
- **English only, CPU transcription.** Whisper `base.en` on CPU is fine for memos but not tuned for
  long, noisy meeting recordings.
- **Live microphone recording wasn't covered by automated testing** (uploaded audio files were).
- **Duplicate commitments.** Approving similar captures twice creates duplicate tracked promises;
  there's no de-duplication.
- **Workflows stay open for the retention period** even for text-only captures that have no media
  to delete.

### Platform
- **Temporal dev server.** Single node, SQLite-backed — not highly available.
- **Media on a local volume.** Encrypted at rest, but on the container host rather than object
  storage.
- **Gateway compatibility.** Capture calls send an `anthropic-beta` header and a `fallbacks` field;
  some corporate gateways strip unknown beta features.
- **No automated tests or evaluation set.** Correctness was verified by running the app and the
  `/debug/*` endpoints; prompt or model changes aren't regression-tested.

## Recommended next steps

| # | Step | Why |
|---|---|---|
| 1 | **Evaluation set + regression tests** — fixed client scenarios with expected ChangeReports; check citation validity, numbers matching source data, section completeness and extraction accuracy; unit tests for `DateResolver` / `ProductResolver` / `BriefEdit` | Makes every later prompt or model change safe to ship; the biggest gap today |
| 2 | **Authentication and authorization** — real RM identity, per-RM client scoping, admin role for the recording policy | Required before any real data |
| 3 | **Production data connector** — FDX-normalized core-banking data behind the existing tool interface | The delta, synthesis, chat and capture layers don't change |
| 4 | **Citable CRM interactions** (`interaction-N`) and a check that each citation supports its claim | Closes the "real but wrong source" gap |
| 5 | **Chat can propose tracked promises**, approved by the RM like capture items | Lets RMs add follow-ups from chat with due-date tracking |
| 6 | **Production platform** — Temporal cluster or Temporal Cloud (with payload encryption), S3/object storage for media, real speaker diarization | Reliability and recording quality |
| 7 | **Capture refinements** — finish text-only workflows immediately; de-duplicate promises | Small fixes found in testing |
| 8 | **Observability** — token usage and latency as metrics/dashboards instead of log lines; per-feature effort tuning | Cost control at scale |
| 9 | **Faster briefs** — stream the brief as it's written, or tune thinking effort for synthesis | Perceived latency |

See [`cba/cost-benefit-analysis.md`](../cba/cost-benefit-analysis.md) for the effort estimate to
harden the POC into a pilot.

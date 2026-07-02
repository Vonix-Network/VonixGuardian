# NIGHTSHIFT — v1.3.0 Planning Stub

Status: **[DRAFT]** — scope not yet locked. This document is a scratchpad for
brainstorming the next wave after v1.2.0 ships. Nothing here is committed
work; treat every item as a candidate until it graduates into a real work
ticket.

## Baseline: v1.2.0

v1.2.0 is the current release baseline. Its full nightshift plan and
retrospective notes have been archived to
[`docs/history/NIGHTSHIFT-v1.2.0.md`](docs/history/NIGHTSHIFT-v1.2.0.md).
Anything shipped in v1.2.0 is out of scope here; this document only tracks
what comes *after*.

## Themes for next wave

The following themes are **ideas only** — [DRAFT], unranked, and subject to
change once v1.2.0 lands and we can look at real-world telemetry / user
feedback.

- **[DRAFT] Cell-string i18n rollout** — extend the localisation surface
  introduced in v1.2.0 to the remaining hard-coded strings (cell contents,
  admin messages, error paths). Needs an audit pass to enumerate remaining
  string sites before we can size the work.
- **[DRAFT] Bedrock / Geyser compat exploration** — investigate whether the
  guardian flows behave correctly for Bedrock clients bridged via Geyser.
  Exploratory only; may result in a compat matrix doc rather than code.
- **[DRAFT] Benchmark suite** — stand up a repeatable perf harness (tick
  cost, cell lookup, event dispatch) so future changes can be measured
  rather than eyeballed. Likely gated on picking a harness framework.
- **[DRAFT] Storage backend abstraction** — factor persistence behind an
  interface so alternative backends (SQL, remote KV) become plausible.
  Big refactor; would need a design doc before any code lands.

## Next step

Before v1.3.0 kicks off: pick 1–2 themes, promote them out of [DRAFT] into
scoped work items, and open real tickets. Everything else stays parked
here as a backlog scratchpad.

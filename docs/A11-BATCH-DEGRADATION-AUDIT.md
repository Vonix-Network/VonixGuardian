# A11 — Batch-Degradation Retry Policy Audit

**Ticket:** VonixGuardian W2-06 (A11)
**Flagged by:** WeedMeister
**Audit date:** 2026-07-01
**Auditor context:** post-incident review of the Berk maintenance-window
truncation at `2026-07-01 08:05:54 UTC` where `vg_actions.target VARCHAR(192)`
was found insufficient for chat / `/tellraw` / sign / explosion payloads.

---

## 1. Scope

WeedMeister raised a concern that Guardian's writer might be running a
**split-and-retry** batch-degradation policy — i.e., on `SQLDataException`
against a batch of N rows, bisect the batch and retry each half at half size,
recurring down to size 1 in an attempt to isolate the poison row. If that
policy re-submits the **surviving N-1 rows individually** after finding the
one bad row, the amplification is catastrophic: a single bad row in a batch of
5000 becomes 4999 individual `INSERT`s, i.e. ~5000× per-row queue-flush latency
for the duration of the recovery.

The Berk field report's mention of "batch size=1" at the moment of failure
was the trigger. We verified this concern against the actual code.

## 2. Actual policy observed

**Source of truth:** `core/src/main/java/network/vonix/guardian/core/queue/BatchedAsyncWriteQueue.java`,
method `flushWithRetry(List<Action> batch)` (lines 245–271).

```java
private void flushWithRetry(List<Action> batch) {
    List<Action> view = Collections.unmodifiableList(batch);
    for (int attempt = 1; attempt <= MAX_SINK_RETRIES; attempt++) {  // MAX_SINK_RETRIES = 3
        try {
            sink.flush(view);
            return;
        } catch (Exception ex) {
            LOG.warn(...);
            if (attempt == MAX_SINK_RETRIES) break;
            try { Thread.sleep(RETRY_BACKOFF_MS); }        // 250 ms
            catch (InterruptedException ie) { ... }
        }
    }
    permanentlyDropped.incrementAndGet();
    LOG.warn(..., "Permanently dropped batch of {} after {} failed attempts",
            batch.size(), MAX_SINK_RETRIES);
}
```

Downstream `sink.flush(view)` calls `AbstractJdbcDao.insertBatch(batch)` which
uses a single `PreparedStatement.executeBatch()` inside one transaction. On
`SQLException` the transaction is rolled back and the exception propagates
straight back to `flushWithRetry`. There is no splitting anywhere.

**Behaviour classification (per the ticket's taxonomy):**

- ❌ (a) *Discard whole batch after retries* — **PARTIALLY.** Retries 3× at
  full size with 250 ms backoff, then drops the entire batch and increments
  `permanentlyDropped`. **No dead-letter queue**; the offending rows are lost
  forever with only a warn-level log.
- ❌ (b) Split-and-retry down to 1, discard offending row — **NOT PRESENT.**
- ❌ (c) Split-and-retry with resubmit of survivors at size 1 (the storm
  scenario) — **NOT PRESENT.**
- ✅ (d) *Something else* — This is closest: naive full-batch retry with a
  hard drop after 3 attempts.

## 3. Where the "batch size=1" in the field report came from

The Berk log line `"JDBC batch failed with data-truncation for column target at
batch size=1"` was **not** the writer having bisected a large batch down to
1. It was the natural steady-state batch size at the moment of failure:
Berk's chat rate at 08:05:54 UTC was ~1 msg / flush-window (see the diag
histogram output — CHAT was the tail of the submitted-by-type distribution
that minute), so the worker's `poll()` returned a single action, the flush
window expired, and it went to the sink with `batch.size() == 1`. Every
subsequent `/tellraw` from the same admin then hit the same truncation on its
own batch-of-1, log-spammed once per second, and lost the message.

**This is important:** the observed behaviour is *not* an amplification
storm. It's a **silent per-row data-loss storm**, which is a different
failure mode and arguably worse for auditability.

## 4. Worst-case amplification analysis (assuming split-retry were introduced)

For completeness, if a future change *did* introduce split-and-retry with
binary bisection until a size-1 isolate is found, and then resubmitted the
survivors at their original batch size (best case, option b done right):

| Batch size | Bisections | Sink calls to isolate 1 poison row |
|-----------:|-----------:|-----------------------------------:|
|          8 |          3 |                                  7 |
|         64 |          6 |                                 13 |
|        512 |          9 |                                 19 |
|       5000 |         13 |                                 27 |

That's ~3× amplification worst case, which is fine.

WeedMeister's storm-scenario (option c: bisect down, then reinsert survivors
one-at-a-time) would give 5000 → 5000+ sink calls, i.e. ~5000× amplification.
Also fine to rule out because **we don't do this**.

## 5. Findings summary

| Concern                                    | Verdict                                                                 |
|--------------------------------------------|-------------------------------------------------------------------------|
| Split-and-retry storm exists today         | **NO** — no bisection logic present.                                    |
| Full batch dropped on persistent failure   | Yes, after 3× retries + 250 ms backoff.                                 |
| Poison-row visibility for operators        | **POOR** — one warn-level log per batch, no dead-letter, no metrics.    |
| Silent data loss on persistent truncation  | **YES** — this is the actual Berk failure mode until A11 lands.         |
| Retry backoff tuning                       | 3 × 250 ms is aggressive for a truncation error (never transient); fine for the intended transient-connection-error case. |

## 6. Recommendation

WeedMeister's specific concern — the write-amplification storm from a naive
split-retry policy — is **not warranted today**. There is no bisection code.
Close that concern out.

However, the audit surfaced a *different* real gap that A11 partially
addresses and W2 should track separately:

### Recommended follow-ups (not part of this ticket)

1. **Distinguish transient vs permanent SQL errors in `flushWithRetry`.**
   `SQLDataException` (SQLSTATE `22xxx` — data-integrity class) will
   **never** succeed on retry. Retrying it 3× with 250 ms backoff is 750 ms
   of pointless wait per truncation before we drop. Detect this class and
   skip straight to dead-letter.

2. **Add a dead-letter path.** On permanent drop, write the offending batch
   to `logs/vg-dead-letter.jsonl` (structured, one action per line, with the
   SQLSTATE + message) so operators can salvage the audit trail post-mortem.
   Currently a poison row means the audit record is gone with only a warn
   log — this is unacceptable for a rollback tool.

3. **Emit a metric.** `guardian.writer.permanently_dropped_total` counter,
   with a label for SQLSTATE class. Berk would have caught the truncation
   inside 30 s if we'd had this on 2026-07-01.

4. **If split-retry is ever introduced later** (e.g. for MySQL deadlock
   handling on very large batches), gate it on `SQLTransientException` /
   deadlock SQLSTATE **only**, and cap the amplification at the natural
   `log2(N)` bisection ceiling — never re-submit survivors individually.

For v1.1.6 (this ticket) we only ship the schema widen + regression test.
The follow-ups above are queued for W2-07 / v1.1.7 planning.

---

**Auditor sign-off:** Concern (c) invalid. Concerns 1–3 above are new,
ticket-worthy, but out of scope for W2-06 / v1.1.6.

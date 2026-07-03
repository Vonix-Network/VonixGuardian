# PERF-NOTES-1.3.0

Per-wave notes on the v1.3.0 async / perf work. One paragraph per wave; append,
don't rewrite. Waves land into `feature/v1.3.0-*` branches and get squashed at
integration time.

## W4 — Diagnostics + kill-switch config

Adds a new operator kill-switch for hot-tick mixin-sourced events plus two new
diagnostic surfaces on `/vg status`. `GuardianConfig.Actions.mixinHotEvents`
(default `true`) sits between the mixin-authored `submit*` calls and the
`EventGate`. When an operator flips it off — via
`/vg config set actions.mixinHotEvents false` — `Guardian.submit(Action)`
short-circuits any action whose `sourceTag` begins with the reserved prefixes
`#fire`, `#natural`, or `#dispenser` (W1a/b/c's classification contract) before
the gate or queue see it, incrementing the existing `gated` counter. This lets
operators shed the hot-tick mixin pipeline under load without a hot-swap or
restart, matching the CoreProtect "safety valve" pattern. A future W2 pass will
fold this predicate into `EventGate` as a first-class short-circuit; the current
placement is intentionally minimal to keep `Guardian.java` diffs small while
that wave is in flight.

On the observability side, `BatchedAsyncWriteQueue` gains a sliding-window
submit-rate meter with 1-second bucket granularity over a 30-second window,
tracked per `ActionType` and as an aggregate "allocation rate". The meter is
lock-free (`AtomicLongArray` for bucket timestamps + `LongAdder` for bucket
counts) and lazy-zeroes stale buckets on write — the write path is one hash
lookup plus one `LongAdder.increment()`, no allocation after the first submit
for a given type. `BatchedAsyncWriteQueue.submitRateByType()` returns an
immutable `Map<String,Double>` of events/sec; `allocationRatePerSecond()`
returns the summed rate. `/vg status` grows a new "Mixin hot events" section
between "Coalescer" and "Event hooks" that displays the kill-switch state, the
current allocation rate, and one row per hot-tick mixin `ActionType` (BURN,
SPREAD, IGNITE, FADE, FORM, LEAVES_DECAY, DISPENSE, ENTITY_CHANGE_BLOCK) with
the observed per-type events/sec.

**Operator playbook.** When `/vg status` reports a mixin `ActionType` at more
than a few hundred submits/sec (or the aggregate allocRate crosses ~5000/s and
the queue depth is climbing), engage the kill-switch:

```
/vg config set actions.mixinHotEvents false
/vg reload
```

This drains the mixin pipeline into `gated` while leaving player-initiated
events (block break/place, container transactions, chat, commands, sessions,
signs) fully live. Verify with `/vg status`: the "Mixin hot events" section
should switch to `enabled  no (kill-switch engaged)`, and the per-type rates
should trend to `0.00/s` within the 30-second window. To re-enable, flip the
flag back on and reload. The flag is hot-swappable — a `/vg reload` picks up
the new value without a restart.

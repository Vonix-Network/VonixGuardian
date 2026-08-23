/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.core.attribution;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v1.3.13 C2 regression tests — {@link FireCauserMemory}.
 *
 * <p>Covers the orphan-fire pairing/suppression contract: an allowlisted
 * entity's break is paired with nearby fire (attributed + shared pairId); a
 * non-allowlisted entity's nearby fire is flagged suppressed; genuine
 * unrelated fire returns no record; TTL and radius bounds hold; and the
 * eviction discipline mirrors {@link TntPrimeMemory}.
 */
class FireCauserMemoryTest {

    private static final String WORLD = "minecraft:overworld";

    private FireCauserMemory mem(AtomicLong clock) {
        return new FireCauserMemory(FireCauserMemory.DEFAULT_TTL_MS,
                FireCauserMemory.DEFAULT_MAX_ENTRIES,
                FireCauserMemory.DEFAULT_RADIUS, clock::get);
    }

    // ------------------------------------------------------------------ pairing

    @Test
    void allowlistedBreakPairsWithFireAtSamePos() {
        AtomicLong clock = new AtomicLong(1_000L);
        FireCauserMemory mem = mem(clock);
        UUID actor = UUID.randomUUID();
        mem.record(WORLD, 10, 64, 20,
                FireCauserMemory.CauserRecord.allowlisted(actor, "Toothless",
                        "isleofberk:nightfury", "#entity", 42L, 1_000L));

        FireCauserMemory.CauserRecord r = mem.consume(WORLD, 10, 64, 20);

        assertThat(r).isNotNull();
        assertThat(r.allowlisted).isTrue();
        assertThat(r.actorUuid).isEqualTo(actor);
        assertThat(r.actorName).isEqualTo("Toothless");
        assertThat(r.entityKey).isEqualTo("isleofberk:nightfury");
        assertThat(r.pairId).isEqualTo(42L);
    }

    @Test
    void allowlistedBreakPairsWithAdjacentFireWithinRadius() {
        AtomicLong clock = new AtomicLong(1_000L);
        FireCauserMemory mem = mem(clock);
        mem.record(WORLD, 10, 64, 20,
                FireCauserMemory.CauserRecord.allowlisted(UUID.randomUUID(), "Drogon",
                        "isleofberk:dragon", "#entity", 7L, 1_000L));

        // Fire lands on the neighbour block (dx=1) a tick later.
        clock.set(1_050L);
        FireCauserMemory.CauserRecord r = mem.consume(WORLD, 11, 64, 20);

        assertThat(r).isNotNull();
        assertThat(r.pairId).isEqualTo(7L);
    }

    @Test
    void fireOutsideRadiusDoesNotPair() {
        AtomicLong clock = new AtomicLong(1_000L);
        FireCauserMemory mem = mem(clock);
        mem.record(WORLD, 10, 64, 20,
                FireCauserMemory.CauserRecord.allowlisted(UUID.randomUUID(), "Dragon",
                        "isleofberk:dragon", "#entity", 1L, 1_000L));

        // 5 blocks away — well outside the default radius of 2.
        FireCauserMemory.CauserRecord r = mem.consume(WORLD, 15, 64, 20);

        assertThat(r).isNull();
    }

    // ------------------------------------------------------------------ suppression

    @Test
    void nonAllowlistedCauserIsFlaggedForSuppression() {
        AtomicLong clock = new AtomicLong(1_000L);
        FireCauserMemory mem = mem(clock);
        mem.record(WORLD, 0, 70, 0,
                FireCauserMemory.CauserRecord.suppressed("isleofberk:wildzippleback", 1_000L));

        FireCauserMemory.CauserRecord r = mem.consume(WORLD, 0, 70, 0);

        assertThat(r).isNotNull();
        assertThat(r.allowlisted).isFalse();
        assertThat(r.entityKey).isEqualTo("isleofberk:wildzippleback");
        assertThat(r.pairId).isEqualTo(0L);
        assertThat(r.actorUuid).isNull();
    }

    @Test
    void noRecordMeansGenuineWorldFire() {
        AtomicLong clock = new AtomicLong(1_000L);
        FireCauserMemory mem = mem(clock);

        // No entity break recorded → player/lightning/lava fire path.
        assertThat(mem.consume(WORLD, 5, 64, 5)).isNull();
    }

    // ------------------------------------------------------------------ TTL

    @Test
    void staleRecordExpiresAndDoesNotPair() {
        AtomicLong clock = new AtomicLong(1_000L);
        FireCauserMemory mem = mem(clock);
        mem.record(WORLD, 3, 64, 3,
                FireCauserMemory.CauserRecord.allowlisted(UUID.randomUUID(), "Dragon",
                        "isleofberk:dragon", "#entity", 9L, 1_000L));

        // Advance past the 2s TTL.
        clock.set(1_000L + FireCauserMemory.DEFAULT_TTL_MS + 1L);
        assertThat(mem.consume(WORLD, 3, 64, 3)).isNull();
    }

    @Test
    void consumeRemovesRecordSoItPairsOnlyOnce() {
        AtomicLong clock = new AtomicLong(1_000L);
        FireCauserMemory mem = mem(clock);
        mem.record(WORLD, 8, 64, 8,
                FireCauserMemory.CauserRecord.allowlisted(UUID.randomUUID(), "Dragon",
                        "isleofberk:dragon", "#entity", 5L, 1_000L));

        assertThat(mem.consume(WORLD, 8, 64, 8)).isNotNull();
        // Second fire on the same spot must not re-pair to the consumed break.
        assertThat(mem.consume(WORLD, 8, 64, 8)).isNull();
    }

    @Test
    void freshestNearbyRecordWinsWhenMultiplePresent() {
        AtomicLong clock = new AtomicLong(1_000L);
        FireCauserMemory mem = mem(clock);
        // Older break at exact pos, newer break one block over.
        mem.record(WORLD, 0, 64, 0,
                FireCauserMemory.CauserRecord.allowlisted(UUID.randomUUID(), "Old",
                        "isleofberk:a", "#entity", 100L, 1_000L));
        clock.set(1_500L);
        mem.record(WORLD, 1, 64, 0,
                FireCauserMemory.CauserRecord.allowlisted(UUID.randomUUID(), "New",
                        "isleofberk:b", "#entity", 200L, 1_500L));
        clock.set(1_600L);

        FireCauserMemory.CauserRecord r = mem.consume(WORLD, 0, 64, 0);
        assertThat(r).isNotNull();
        assertThat(r.pairId).isEqualTo(200L);
        assertThat(r.actorName).isEqualTo("New");
    }

    // ------------------------------------------------------------------ hygiene

    @Test
    void clearEmptiesTheCache() {
        AtomicLong clock = new AtomicLong(1_000L);
        FireCauserMemory mem = mem(clock);
        mem.record(WORLD, 1, 1, 1,
                FireCauserMemory.CauserRecord.suppressed("x:y", 1_000L));
        assertThat(mem.size()).isEqualTo(1);
        mem.clear();
        assertThat(mem.size()).isEqualTo(0);
    }

    @Test
    void overCapHardEvictionIsBoundedAndKeepsCacheWithinLimit() {
        AtomicLong clock = new AtomicLong(1_000L);
        int maxEntries = 4;
        FireCauserMemory mem = new FireCauserMemory(2_000L, maxEntries, 0, clock::get);

        for (int i = 0; i < FireCauserMemory.HARD_EVICT_STRIDE + maxEntries; i++) {
            mem.record(WORLD, i, 64, 0,
                    FireCauserMemory.CauserRecord.suppressed("mod:entity", clock.get()));
        }

        assertThat(mem.hardEvictInvocations()).isEqualTo(1L);
        assertThat(mem.size()).isLessThanOrEqualTo(maxEntries);
    }

    @Test
    void overwriteChurnKeepsSizeWithinDocumentedHeadroom() {
        int cap = 64;
        AtomicLong clock = new AtomicLong(1_000L);
        FireCauserMemory mem = new FireCauserMemory(60_000L, cap, 0, clock::get);
        int churnKeys = 4;
        int rounds = cap * 20;
        UUID actor = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        for (int i = 0; i < rounds; i++) {
            long now = clock.incrementAndGet();
            mem.record(WORLD, 10_000 + i, 64, 0,
                    FireCauserMemory.CauserRecord.suppressed("mod:entity", now));
            for (int w = 0; w < FireCauserMemory.HARD_EVICT_ARBITRARY_CAP; w++) {
                now = clock.incrementAndGet();
                mem.record(WORLD, w % churnKeys, 64, 0,
                        FireCauserMemory.CauserRecord.allowlisted(
                                actor, "Overwrite", "mod:dragon", "#entity", now, now));
            }
        }
        int upperBound = cap + FireCauserMemory.HARD_EVICT_STRIDE
                + FireCauserMemory.HARD_EVICT_ARBITRARY_CAP;
        assertThat(mem.hardEvictInvocations()).isGreaterThan(0L);
        assertThat(mem.size())
                .as("overwrite-churn must stay within documented cap+headroom")
                .isLessThanOrEqualTo(upperBound);
        assertThat(mem.peek(WORLD, 0, 64, 0)).isNotNull();
    }

    @Test
    void hardEvictionSkipsStaleOverwrittenCandidate() {
        AtomicLong clock = new AtomicLong(1_000L);
        int maxEntries = 1;
        FireCauserMemory mem = new FireCauserMemory(2_000L, maxEntries, 0, clock::get);
        FireCauserMemory.CauserRecord old = FireCauserMemory.CauserRecord.suppressed("old:entity", clock.get());
        FireCauserMemory.CauserRecord replacement = FireCauserMemory.CauserRecord.suppressed("new:entity", clock.get());

        mem.record(WORLD, 0, 64, 0, old);
        for (int i = 0; i < FireCauserMemory.HARD_EVICT_STRIDE - 1; i++) {
            mem.record(WORLD, i + 1, 64, 0,
                    FireCauserMemory.CauserRecord.suppressed("mod:entity", clock.get()));
        }
        mem.record(WORLD, 0, 64, 0, replacement);

        assertThat(mem.hardEvictInvocations()).isEqualTo(1L);
        assertThat(mem.consume(WORLD, 0, 64, 0)).isNotNull()
                .extracting(r -> r.entityKey).isEqualTo("new:entity");
    }

    @Test
    void nullWorldIsIgnoredOnRecordAndConsume() {
        AtomicLong clock = new AtomicLong(1_000L);
        FireCauserMemory mem = mem(clock);
        mem.record(null, 0, 0, 0,
                FireCauserMemory.CauserRecord.suppressed("x:y", 1_000L));
        assertThat(mem.size()).isEqualTo(0);
        assertThat(mem.consume(null, 0, 0, 0)).isNull();
    }

    @Test
    void constructorRejectsBadArgs() {
        try { new FireCauserMemory(0L, 10, 2, System::currentTimeMillis); assertThat(false).isTrue(); }
        catch (IllegalArgumentException expected) { /* ok */ }
        try { new FireCauserMemory(10L, 0, 2, System::currentTimeMillis); assertThat(false).isTrue(); }
        catch (IllegalArgumentException expected) { /* ok */ }
        try { new FireCauserMemory(10L, 10, -1, System::currentTimeMillis); assertThat(false).isTrue(); }
        catch (IllegalArgumentException expected) { /* ok */ }
    }

    // ------------------------------------------------------------------ resolver verdicts

    @Test
    void resolverPairsAllowlistedCauser() {
        AtomicLong clock = new AtomicLong(1_000L);
        FireCauserMemory mem = mem(clock);
        UUID actor = UUID.randomUUID();
        mem.record(WORLD, 10, 64, 20,
                FireCauserMemory.CauserRecord.allowlisted(actor, "Toothless",
                        "isleofberk:nightfury", "#entity", 42L, 1_000L));

        UniversalAttribution.FireCauser v =
                UniversalAttribution.resolveFireCauser(mem, WORLD, 10, 64, 20);

        assertThat(v.verdict).isEqualTo(UniversalAttribution.FireVerdict.PAIR);
        assertThat(v.actorUuid).isEqualTo(actor);
        assertThat(v.actorName).isEqualTo("Toothless");
        assertThat(v.sourceTag).isEqualTo("#entity");
        assertThat(v.pairId).isEqualTo(42L);
        assertThat(UniversalAttribution.takePendingFirePairId()).isEqualTo(42L);
        assertThat(UniversalAttribution.takePendingFirePairId()).isNull();
    }

    @Test
    void pairIdAt_returnsExactAllowlistedToken() {
        AtomicLong clock = new AtomicLong(1_000L);
        FireCauserMemory mem = mem(clock);
        mem.record(WORLD, 10, 64, 20,
                FireCauserMemory.CauserRecord.allowlisted(UUID.randomUUID(), "Toothless",
                        "isleofberk:nightfury", "#entity", 42L, 1_000L));
        assertThat(mem.pairIdAt(WORLD, 10, 64, 20)).isEqualTo(42L);
        assertThat(mem.pairIdAt(WORLD, 11, 64, 20)).isNull();
    }

    @Test
    void timestampPassedAsPairId_isReplacedWithUniqueToken() {
        AtomicLong clock = new AtomicLong(5_000L);
        FireCauserMemory mem = mem(clock);
        mem.record(WORLD, 1, 64, 1,
                FireCauserMemory.CauserRecord.allowlisted(UUID.randomUUID(), "A",
                        "mod:dragon", "#entity", 5_000L, 5_000L));
        mem.record(WORLD, 2, 64, 2,
                FireCauserMemory.CauserRecord.allowlisted(UUID.randomUUID(), "B",
                        "mod:dragon", "#entity", 5_000L, 5_000L));
        Long a = mem.pairIdAt(WORLD, 1, 64, 1);
        Long b = mem.pairIdAt(WORLD, 2, 64, 2);
        assertThat(a).isNotNull().isNotEqualTo(5_000L).isNotEqualTo(0L);
        assertThat(b).isNotNull().isNotEqualTo(5_000L).isNotEqualTo(0L);
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void resolverSuppressesNonAllowlistedCauser() {
        AtomicLong clock = new AtomicLong(1_000L);
        FireCauserMemory mem = mem(clock);
        mem.record(WORLD, 0, 70, 0,
                FireCauserMemory.CauserRecord.suppressed("isleofberk:zippleback", 1_000L));

        UniversalAttribution.FireCauser v =
                UniversalAttribution.resolveFireCauser(mem, WORLD, 0, 70, 0);

        assertThat(v.verdict).isEqualTo(UniversalAttribution.FireVerdict.SUPPRESS);
        assertThat(v.actorUuid).isNull();
    }

    @Test
    void resolverPassesThroughGenuineWorldFire() {
        AtomicLong clock = new AtomicLong(1_000L);
        FireCauserMemory mem = mem(clock);

        UniversalAttribution.FireCauser v =
                UniversalAttribution.resolveFireCauser(mem, WORLD, 5, 64, 5);

        assertThat(v.verdict).isEqualTo(UniversalAttribution.FireVerdict.PASSTHROUGH);
    }

    @Test
    void sameTimestampReusesCallerRecordIdentity() {
        AtomicLong clock = new AtomicLong(1_000L);
        FireCauserMemory mem = mem(clock);
        FireCauserMemory.CauserRecord rec = FireCauserMemory.CauserRecord.allowlisted(
                UUID.randomUUID(), "Toothless", "isleofberk:nightfury", "#entity", 42L, clock.get());

        mem.record(WORLD, 4, 64, 4, rec);

        assertThat(mem.peek(WORLD, 4, 64, 4)).isSameAs(rec);
        assertThat(mem.consume(WORLD, 4, 64, 4)).isSameAs(rec);
    }

    @Test
    void mismatchedTimestampCopiesTheRecord() {
        AtomicLong clock = new AtomicLong(1_000L);
        FireCauserMemory mem = mem(clock);
        FireCauserMemory.CauserRecord rec = FireCauserMemory.CauserRecord.allowlisted(
                UUID.randomUUID(), "Toothless", "isleofberk:nightfury", "#entity", 42L, 1L);

        mem.record(WORLD, 4, 64, 4, rec);

        FireCauserMemory.CauserRecord stored = mem.consume(WORLD, 4, 64, 4);
        assertThat(stored).isNotSameAs(rec);
        assertThat(stored.causedAtMillis).isEqualTo(1_000L);
        assertThat(stored.pairId).isEqualTo(42L);
    }

    @Test
    void peekDoesNotConsumeAndStillSelectsFreshestWithinRadius() {
        AtomicLong clock = new AtomicLong(1_000L);
        FireCauserMemory mem = mem(clock);
        mem.record(WORLD, 0, 64, 0,
                FireCauserMemory.CauserRecord.allowlisted(UUID.randomUUID(), "Old",
                        "isleofberk:a", "#entity", 100L, 1_000L));
        clock.set(1_500L);
        mem.record(WORLD, 1, 64, 0,
                FireCauserMemory.CauserRecord.allowlisted(UUID.randomUUID(), "New",
                        "isleofberk:b", "#entity", 200L, 1_500L));
        clock.set(1_600L);

        FireCauserMemory.CauserRecord peeked = mem.peek(WORLD, 0, 64, 0);
        assertThat(peeked).isNotNull();
        assertThat(peeked.pairId).isEqualTo(200L);
        assertThat(mem.size()).isEqualTo(2);
        FireCauserMemory.CauserRecord consumed = mem.consume(WORLD, 0, 64, 0);
        assertThat(consumed).isSameAs(peeked);
        assertThat(mem.size()).isEqualTo(1);
        assertThat(mem.consume(WORLD, 0, 64, 0)).isNotNull()
                .extracting(r -> r.pairId).isEqualTo(100L);
    }

    @Test
    void equalTimestampTieKeepsFirstScanHit() {
        AtomicLong clock = new AtomicLong(1_000L);
        FireCauserMemory mem = new FireCauserMemory(2_000L, 16, 1, clock::get);
        mem.record(WORLD, 1, 64, 0,
                FireCauserMemory.CauserRecord.allowlisted(UUID.randomUUID(), "PosX",
                        "isleofberk:a", "#entity", 2L, 1_000L));
        mem.record(WORLD, 0, 64, 0,
                FireCauserMemory.CauserRecord.allowlisted(UUID.randomUUID(), "Origin",
                        "isleofberk:b", "#entity", 1L, 1_000L));

        // Scan starts at dx=-1, so ( -1,0,0) misses, then (0,0,0) is first equal-ts hit.
        FireCauserMemory.CauserRecord r = mem.consume(WORLD, 0, 64, 0);
        assertThat(r).isNotNull();
        assertThat(r.pairId).isEqualTo(1L);
        assertThat(mem.consume(WORLD, 1, 64, 0)).isNotNull()
                .extracting(rec -> rec.pairId).isEqualTo(2L);
    }

    @Test
    void overwriteSamePositionReplacesRecord() {
        AtomicLong clock = new AtomicLong(1_000L);
        FireCauserMemory mem = mem(clock);
        mem.record(WORLD, 2, 64, 2,
                FireCauserMemory.CauserRecord.allowlisted(UUID.randomUUID(), "Old",
                        "old:entity", "#entity", 1L, 1_000L));
        clock.set(1_100L);
        mem.record(WORLD, 2, 64, 2,
                FireCauserMemory.CauserRecord.allowlisted(UUID.randomUUID(), "New",
                        "new:entity", "#entity", 2L, 1_100L));

        assertThat(mem.size()).isEqualTo(1);
        assertThat(mem.consume(WORLD, 2, 64, 2))
                .extracting(r -> r.pairId).isEqualTo(2L);
        assertThat(mem.consume(WORLD, 2, 64, 2)).isNull();
    }

    @Test
    void clearResetsHardEvictCounter() {
        AtomicLong clock = new AtomicLong(1_000L);
        int maxEntries = 4;
        FireCauserMemory mem = new FireCauserMemory(2_000L, maxEntries, 0, clock::get);
        int inserts = FireCauserMemory.HARD_EVICT_STRIDE + maxEntries - 1;
        for (int i = 0; i < inserts; i++) {
            mem.record(WORLD, i, 64, 0,
                    FireCauserMemory.CauserRecord.suppressed("mod:entity", clock.get()));
        }
        assertThat(mem.hardEvictInvocations()).isZero();
        mem.clear();
        for (int i = 0; i < inserts; i++) {
            mem.record(WORLD, 10_000 + i, 64, 0,
                    FireCauserMemory.CauserRecord.suppressed("mod:entity", clock.get()));
        }
        assertThat(mem.hardEvictInvocations()).isZero();
        assertThat(mem.size()).isGreaterThan(0);
    }
}

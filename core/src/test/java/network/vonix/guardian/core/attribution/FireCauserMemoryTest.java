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
 * v1.3.10 C2 regression tests — {@link FireCauserMemory}.
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
}

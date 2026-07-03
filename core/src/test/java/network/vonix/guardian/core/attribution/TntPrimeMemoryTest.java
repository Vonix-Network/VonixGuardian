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
 * v1.3.1 X7 regression tests — {@link TntPrimeMemory} + {@link UniversalAttribution}.
 *
 * <p>Covers the four attribution paths named in the CoreProtect-parity gap
 * G-CP-2 investigation: fire spread, redstone charge, dispenser
 * flint&amp;steel, and player right-click flint&amp;steel. The core module
 * has no MC types, so we exercise the pure {@link TntPrimeMemory} API and
 * assert the {@link UniversalAttribution} promotion behavior.
 */
class TntPrimeMemoryTest {

    private static final String WORLD = "minecraft:overworld";

    // ------------------------------------------------------------------ 4 scenarios

    /**
     * Scenario 1 — <strong>Player right-click F&amp;S on TNT</strong>. The
     * TntBlockMixin captures the Player igniter into the memory; the
     * resolver later consumes it and promotes the sentinel to
     * PLAYER_DIRECT.
     */
    @Test
    void playerFlintAndSteelPromotesToPlayerDirect() {
        TntPrimeMemory mem = new TntPrimeMemory();
        UUID actor = UUID.randomUUID();
        mem.record(WORLD, 10, 64, 20,
                TntPrimeMemory.PrimeRecord.player(actor, "Notch", 1_000L));

        Attribution attr = UniversalAttribution.resolveTntPrime(
                mem, WORLD, 10, 64, 20, "#tnt");

        assertThat(attr).isNotNull();
        assertThat(attr.actorUuid()).isEqualTo(actor);
        assertThat(attr.actorName()).isEqualTo("Notch");
        assertThat(attr.kind()).isEqualTo(AttributionKind.PLAYER_DIRECT);
        assertThat(attr.entitySentinel()).isEqualTo("#tnt");
        assertThat(attr.chainHops()).isEqualTo(0);
    }

    /**
     * Scenario 2 — <strong>Redstone-primed TNT</strong>. TntBlock.explode is
     * called with a null LivingEntity igniter. TntBlockMixin doesn't record
     * anything (no player available). Detonate handler falls back to sentinel.
     */
    @Test
    void redstonePrimeWithoutPlayerFallsThroughToSentinel() {
        TntPrimeMemory mem = new TntPrimeMemory();
        // No record() call — redstone chain doesn't feed one in.

        Attribution attr = UniversalAttribution.resolveTntPrime(
                mem, WORLD, 5, 64, 5, "#tnt");

        assertThat(attr).isNull(); // caller falls back to normal resolver → sentinel.
    }

    /**
     * Scenario 3 — <strong>Dispenser flint&amp;steel</strong>. Same as
     * redstone: TntBlock.explode is called with a null LivingEntity because
     * fire propagates from a dispenser-lit fire block. No player attribution
     * without additional fire-chain tracking (deferred to a follow-up wave).
     * The infrastructure IS ready: the caller can record a
     * {@link TntPrimeMemory.PrimeRecord#dispenser} once the fire-chain
     * memory lands. Verified here by explicitly recording a dispenser
     * event and asserting the source-tag hint round-trips.
     */
    @Test
    void dispenserPrimeRoundTripsSourceTagHint() {
        TntPrimeMemory mem = new TntPrimeMemory();
        mem.record(WORLD, 100, 64, 100,
                TntPrimeMemory.PrimeRecord.dispenser(null, null, 2_000L));

        TntPrimeMemory.PrimeRecord rec = UniversalAttribution.consumeTntPrime(
                mem, WORLD, 100, 64, 100);

        assertThat(rec).isNotNull();
        assertThat(rec.sourceTagHint).isEqualTo("#dispenser");
        assertThat(rec.actorUuid).isNull(); // no player without fire-chain
    }

    /**
     * Scenario 4 — <strong>Fire spread onto TNT</strong>. Same shape as
     * dispenser — no player without an upstream fire-chain memory. Verifies
     * infrastructure round-trip.
     */
    @Test
    void fireSpreadPrimeRoundTripsSourceTagHint() {
        TntPrimeMemory mem = new TntPrimeMemory();
        mem.record(WORLD, -20, 5, -30,
                TntPrimeMemory.PrimeRecord.fire(null, null, 3_000L));

        TntPrimeMemory.PrimeRecord rec = UniversalAttribution.consumeTntPrime(
                mem, WORLD, -20, 5, -30);

        assertThat(rec).isNotNull();
        assertThat(rec.sourceTagHint).isEqualTo("#fire");
    }

    // ------------------------------------------------------------------ memory semantics

    /** A record older than the TTL is not returned. */
    @Test
    void ttlExpiredRecordsAreNotReturned() {
        AtomicLong now = new AtomicLong(0L);
        TntPrimeMemory mem = new TntPrimeMemory(1_000L, 128, now::get);
        UUID actor = UUID.randomUUID();
        mem.record(WORLD, 1, 1, 1,
                TntPrimeMemory.PrimeRecord.player(actor, "Alex", now.get()));
        now.set(2_000L); // 2 s later, TTL = 1 s → stale
        assertThat(UniversalAttribution.resolveTntPrime(mem, WORLD, 1, 1, 1, "#tnt")).isNull();
    }

    /** consume() removes the record so the same TNT can't be double-billed. */
    @Test
    void consumeRemovesTheRecord() {
        TntPrimeMemory mem = new TntPrimeMemory();
        UUID actor = UUID.randomUUID();
        mem.record(WORLD, 7, 7, 7,
                TntPrimeMemory.PrimeRecord.player(actor, "Steve", 100L));
        assertThat(mem.size()).isEqualTo(1);
        UniversalAttribution.consumeTntPrime(mem, WORLD, 7, 7, 7);
        assertThat(mem.size()).isZero();
        // Second consume returns null.
        assertThat(UniversalAttribution.consumeTntPrime(mem, WORLD, 7, 7, 7)).isNull();
    }

    /** peek() returns the record without removing it. */
    @Test
    void peekDoesNotRemoveTheRecord() {
        TntPrimeMemory mem = new TntPrimeMemory();
        UUID actor = UUID.randomUUID();
        mem.record(WORLD, 42, 64, 42,
                TntPrimeMemory.PrimeRecord.player(actor, "Notch", 100L));
        assertThat(mem.peek(WORLD, 42, 64, 42)).isNotNull();
        assertThat(mem.size()).isEqualTo(1);
    }

    /**
     * Miss on a nearby but distinct position returns null (no fuzzy match).
     * We rely on {@code entity.blockPosition()} being exactly the priming
     * block; a 1-block drift returns null. This is intentional: fuzzy match
     * would risk cross-attribution when two players prime adjacent TNT.
     */
    @Test
    void adjacentPositionsDoNotCrossAttribute() {
        TntPrimeMemory mem = new TntPrimeMemory();
        UUID actor = UUID.randomUUID();
        mem.record(WORLD, 0, 64, 0,
                TntPrimeMemory.PrimeRecord.player(actor, "Alex", 100L));
        assertThat(UniversalAttribution.resolveTntPrime(mem, WORLD, 1, 64, 0, "#tnt")).isNull();
        assertThat(UniversalAttribution.resolveTntPrime(mem, WORLD, 0, 64, 0, "#tnt")).isNotNull();
    }

    /** Different worlds don't cross-attribute. */
    @Test
    void differentWorldsDoNotCrossAttribute() {
        TntPrimeMemory mem = new TntPrimeMemory();
        UUID actor = UUID.randomUUID();
        mem.record("minecraft:overworld", 0, 64, 0,
                TntPrimeMemory.PrimeRecord.player(actor, "Alex", 100L));
        assertThat(UniversalAttribution.resolveTntPrime(mem, "minecraft:the_nether", 0, 64, 0, "#tnt")).isNull();
        assertThat(UniversalAttribution.resolveTntPrime(mem, "minecraft:overworld", 0, 64, 0, "#tnt")).isNotNull();
    }

    /** Over-cap puts trigger eviction so the map never grows unboundedly. */
    @Test
    void overCapEvictsOldestEntries() {
        AtomicLong now = new AtomicLong(0L);
        TntPrimeMemory mem = new TntPrimeMemory(10_000L, 4, now::get);
        for (int i = 0; i < 20; i++) {
            now.set(1_000L + i);
            mem.record(WORLD, i, 0, 0,
                    TntPrimeMemory.PrimeRecord.player(UUID.randomUUID(), "P" + i, now.get()));
        }
        assertThat(mem.size()).isLessThanOrEqualTo(4);
    }

    /** Null worldId / null actor are ignored (defensive). */
    @Test
    void nullInputsAreRejectedSafely() {
        TntPrimeMemory mem = new TntPrimeMemory();
        mem.record(null, 0, 0, 0, TntPrimeMemory.PrimeRecord.player(UUID.randomUUID(), "X", 1L));
        mem.record(WORLD, 0, 0, 0, null);
        assertThat(mem.size()).isZero();
        assertThat(mem.consume(null, 0, 0, 0)).isNull();
    }

    /** clear() empties the cache. */
    @Test
    void clearEmptiesTheCache() {
        TntPrimeMemory mem = new TntPrimeMemory();
        mem.record(WORLD, 1, 1, 1, TntPrimeMemory.PrimeRecord.player(UUID.randomUUID(), "P", 1L));
        mem.record(WORLD, 2, 2, 2, TntPrimeMemory.PrimeRecord.player(UUID.randomUUID(), "P", 1L));
        assertThat(mem.size()).isEqualTo(2);
        mem.clear();
        assertThat(mem.size()).isZero();
    }

    /**
     * CoreProtect parity: player-scoped attribution keeps the entity
     * sentinel string (so /vg lookup by #tnt still surfaces the row) but
     * flips actor/name/kind to the human. Matches CoreProtect's TNTPrimeListener
     * behaviour of queuing a block-break with the priming player.
     */
    @Test
    void promotedAttributionPreservesSentinelForLookupParity() {
        TntPrimeMemory mem = new TntPrimeMemory();
        UUID actor = UUID.randomUUID();
        mem.record(WORLD, 0, 0, 0,
                TntPrimeMemory.PrimeRecord.player(actor, "Notch", 500L));
        Attribution attr = UniversalAttribution.resolveTntPrime(
                mem, WORLD, 0, 0, 0, "#tnt");

        assertThat(attr.entitySentinel()).isEqualTo("#tnt");
        assertThat(attr.actorName()).isEqualTo("Notch");
        assertThat(attr.kind().isPlayer()).isTrue();
    }
}

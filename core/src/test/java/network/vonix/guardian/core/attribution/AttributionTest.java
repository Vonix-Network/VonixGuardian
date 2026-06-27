/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.core.attribution;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AttributionTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String SENTINEL = "#mob:isleofberk:skrill";

    @Test
    void directBuildsPlayerAttribution() {
        Attribution a = Attribution.direct(PLAYER, "Notch", SENTINEL);
        assertThat(a.kind()).isEqualTo(AttributionKind.PLAYER_DIRECT);
        assertThat(a.actorUuid()).isEqualTo(PLAYER);
        assertThat(a.actorName()).isEqualTo("Notch");
        assertThat(a.entitySentinel()).isEqualTo(SENTINEL);
        assertThat(a.chainHops()).isZero();
    }

    @Test
    void riderHasZeroHops() {
        Attribution a = Attribution.rider(PLAYER, "Notch", SENTINEL);
        assertThat(a.kind()).isEqualTo(AttributionKind.PLAYER_RIDER);
        assertThat(a.chainHops()).isZero();
    }

    @Test
    void projectileCanCarryHops() {
        Attribution a = Attribution.projectile(PLAYER, "Notch", SENTINEL, 2);
        assertThat(a.kind()).isEqualTo(AttributionKind.PLAYER_PROJECTILE);
        assertThat(a.chainHops()).isEqualTo(2);
    }

    @Test
    void naturalAttributionHasNoActorUuid() {
        Attribution a = Attribution.natural(AttributionKind.NATURAL_RAID, "#mob:minecraft:ravager");
        assertThat(a.kind()).isEqualTo(AttributionKind.NATURAL_RAID);
        assertThat(a.actorUuid()).isNull();
        assertThat(a.actorName()).isEqualTo("#mob:minecraft:ravager");
    }

    @Test
    void naturalRejectsNonNaturalKind() {
        assertThatThrownBy(() -> Attribution.natural(AttributionKind.PLAYER_RIDER, SENTINEL))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void playerKindRequiresActorUuid() {
        assertThatThrownBy(() ->
            new Attribution(null, "Notch", AttributionKind.PLAYER_DIRECT, SENTINEL, 0))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void negativeChainHopsRejected() {
        assertThatThrownBy(() ->
            new Attribution(PLAYER, "Notch", AttributionKind.PLAYER_DIRECT, SENTINEL, -1))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unknownDefaults() {
        Attribution a = Attribution.unknown(SENTINEL);
        assertThat(a.kind()).isEqualTo(AttributionKind.UNKNOWN);
        assertThat(a.actorUuid()).isNull();
        assertThat(a.actorName()).isEqualTo(SENTINEL);
        assertThat(a.entitySentinel()).isEqualTo(SENTINEL);
    }

    @Test
    void nullKindRejected() {
        assertThatThrownBy(() ->
            new Attribution(PLAYER, "Notch", null, SENTINEL, 0))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void nullSentinelRejected() {
        assertThatThrownBy(() ->
            new Attribution(PLAYER, "Notch", AttributionKind.PLAYER_DIRECT, null, 0))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void kindPredicatesAreCorrect() {
        for (AttributionKind k : AttributionKind.values()) {
            // Exactly one of isPlayer / isNatural / UNKNOWN.
            boolean p = k.isPlayer();
            boolean n = k.isNatural();
            assertThat(p && n).isFalse();
            if (k == AttributionKind.UNKNOWN) {
                assertThat(p).isFalse();
                assertThat(n).isFalse();
            }
        }
    }
}

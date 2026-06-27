package network.vonix.guardian.core.perms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import network.vonix.guardian.core.config.GuardianConfig;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link PermissionResolver}.
 *
 * <p>LuckPerms is not on the test classpath; the resolver therefore observes the LP probe as
 * permanently unavailable. We exercise:
 *
 * <ul>
 *   <li>Fallback path when {@code useLuckPerms=false}.
 *   <li>Fallback path when {@code useLuckPerms=true} but LP is absent (and that the result is
 *       cached so the LP probe runs at most once).
 *   <li>Op-level threshold semantics.
 *   <li>Defensive handling when the {@link OpLevelFallback} throws.
 *   <li>Null-argument rejection.
 * </ul>
 */
class PermissionResolverTest {

    private static GuardianConfig.Permissions cfg(boolean useLp, int defaultOp) {
        return new GuardianConfig.Permissions(useLp, defaultOp);
    }

    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Test
    void useLuckPermsFalse_alwaysUsesFallback_andNeverProbesLp() {
        Map<UUID, Integer> ops = new HashMap<>();
        ops.put(ALICE, 4);
        ops.put(BOB, 0);

        AtomicInteger calls = new AtomicInteger();
        OpLevelFallback fb =
                uuid -> {
                    calls.incrementAndGet();
                    return ops.getOrDefault(uuid, 0);
                };

        PermissionResolver r = new PermissionResolver(cfg(false, 2), fb);

        assertThat(r.has(ALICE, "guardian.lookup")).isTrue();
        assertThat(r.has(BOB, "guardian.lookup")).isFalse();
        assertThat(calls.get()).isEqualTo(2);

        // LP must remain unprobed since useLuckPerms=false short-circuits.
        assertThat(r.lpAvailabilityCacheForTest()).isNull();
    }

    @Test
    void useLuckPermsTrue_butLpAbsent_cachesUnavailableAndUsesFallback() {
        AtomicInteger fbCalls = new AtomicInteger();
        OpLevelFallback fb =
                uuid -> {
                    fbCalls.incrementAndGet();
                    return uuid.equals(ALICE) ? 4 : 0;
                };

        PermissionResolver r = new PermissionResolver(cfg(true, 2), fb);

        assertThat(r.has(ALICE, "guardian.rollback")).isTrue();
        assertThat(r.has(BOB, "guardian.rollback")).isFalse();

        // After first call, LP must be cached as permanently unavailable.
        assertThat(r.lpAvailabilityCacheForTest()).isEqualTo(Boolean.FALSE);
        assertThat(fbCalls.get()).isEqualTo(2);
    }

    @Test
    void opLevelThreshold_isInclusive() {
        OpLevelFallback fb = uuid -> 2;
        PermissionResolver r = new PermissionResolver(cfg(false, 2), fb);
        assertThat(r.has(ALICE, "x")).isTrue();

        PermissionResolver strict = new PermissionResolver(cfg(false, 3), fb);
        assertThat(strict.has(ALICE, "x")).isFalse();
    }

    @Test
    void opLevelZero_withDefaultZero_grantsEveryone() {
        OpLevelFallback fb = uuid -> 0;
        PermissionResolver r = new PermissionResolver(cfg(false, 0), fb);
        assertThat(r.has(ALICE, "x")).isTrue();
    }

    @Test
    void fallbackThrowing_isCaughtAndDenies() {
        OpLevelFallback fb =
                uuid -> {
                    throw new RuntimeException("boom");
                };
        PermissionResolver r = new PermissionResolver(cfg(false, 2), fb);
        assertThat(r.has(ALICE, "x")).isFalse();
    }

    @Test
    void cacheResetForcesReprobe() {
        OpLevelFallback fb = uuid -> 4;
        PermissionResolver r = new PermissionResolver(cfg(true, 2), fb);

        r.has(ALICE, "x");
        assertThat(r.lpAvailabilityCacheForTest()).isEqualTo(Boolean.FALSE);

        r.resetLpAvailabilityCacheForTest();
        assertThat(r.lpAvailabilityCacheForTest()).isNull();

        r.has(ALICE, "x");
        assertThat(r.lpAvailabilityCacheForTest()).isEqualTo(Boolean.FALSE);
    }

    @Test
    void nullArguments_rejected() {
        PermissionResolver r = new PermissionResolver(cfg(false, 2), uuid -> 0);
        assertThatThrownBy(() -> r.has(null, "x")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> r.has(ALICE, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void constructor_rejectsNulls() {
        assertThatThrownBy(() -> new PermissionResolver(null, uuid -> 0))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PermissionResolver(cfg(false, 2), null))
                .isInstanceOf(NullPointerException.class);
    }
}

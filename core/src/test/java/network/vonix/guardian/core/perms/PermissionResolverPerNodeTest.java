package network.vonix.guardian.core.perms;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import network.vonix.guardian.core.config.GuardianConfig;
import org.junit.jupiter.api.Test;

/**
 * W3-B8: per-node op-level fallback semantics.
 *
 * <p>LuckPerms is not on the test classpath, so LP is observed as permanently unavailable — the
 * "LP present + TRUE" and "LP present + UNSET" branches are exercised indirectly via
 * {@link PermissionResolver#has(UUID, String)}'s LP path in {@link PermissionResolverTest}; here
 * we focus on the fallback-path semantics of the enum overload plus the per-node override map.
 */
class PermissionResolverPerNodeTest {

    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void lpAbsent_opLevelBelowNodeDefault_denies() {
        // ROLLBACK default = 3; op = 2 → deny.
        GuardianConfig.Permissions cfg = new GuardianConfig.Permissions(false, 0, Map.of());
        PermissionResolver r = new PermissionResolver(cfg, uuid -> 2);
        assertThat(r.has(ALICE, PermissionNode.ROLLBACK)).isFalse();
    }

    @Test
    void lpAbsent_opLevelMeetsNodeDefault_grants() {
        // LOOKUP default = 2; op = 2 → grant.
        GuardianConfig.Permissions cfg = new GuardianConfig.Permissions(false, 0, Map.of());
        PermissionResolver r = new PermissionResolver(cfg, uuid -> 2);
        assertThat(r.has(ALICE, PermissionNode.LOOKUP)).isTrue();
    }

    @Test
    void lpAbsent_opLevelAboveNodeDefault_grants() {
        // PURGE default = 4; op = 4 → grant.
        GuardianConfig.Permissions cfg = new GuardianConfig.Permissions(false, 0, Map.of());
        PermissionResolver r = new PermissionResolver(cfg, uuid -> 4);
        assertThat(r.has(ALICE, PermissionNode.PURGE)).isTrue();
    }

    @Test
    void perNodeOpLevel_defaultWhenNoOverride() {
        GuardianConfig.Permissions cfg = new GuardianConfig.Permissions(false, 0, Map.of());
        PermissionResolver r = new PermissionResolver(cfg, uuid -> 0);
        assertThat(r.perNodeOpLevel(PermissionNode.LOOKUP))
                .isEqualTo(PermissionNode.LOOKUP.defaultOpLevel());
        assertThat(r.perNodeOpLevel(PermissionNode.PURGE))
                .isEqualTo(PermissionNode.PURGE.defaultOpLevel());
    }

    @Test
    void perNodeOpLevel_overrideReplacesDefault() {
        // Raise LOOKUP from 2 → 4, lower PURGE from 4 → 1.
        Map<String, Integer> overrides = Map.of(
                PermissionNode.LOOKUP.node(), 4,
                PermissionNode.PURGE.node(), 1
        );
        GuardianConfig.Permissions cfg = new GuardianConfig.Permissions(false, 0, overrides);
        PermissionResolver r = new PermissionResolver(cfg, uuid -> 2);

        assertThat(r.perNodeOpLevel(PermissionNode.LOOKUP)).isEqualTo(4);
        assertThat(r.perNodeOpLevel(PermissionNode.PURGE)).isEqualTo(1);

        // LOOKUP now requires 4; op=2 → deny.
        assertThat(r.has(ALICE, PermissionNode.LOOKUP)).isFalse();
        // PURGE lowered to 1; op=2 → grant.
        assertThat(r.has(ALICE, PermissionNode.PURGE)).isTrue();
    }

    @Test
    void legacyCommandStringUsesMappedPermissionNodeFallback() {
        // Command cells historically pass vonixguardian.command.rollback. When
        // LuckPerms is absent, fallback must use PermissionNode.ROLLBACK's op=3
        // default rather than coarse defaultOpLevel=2.
        GuardianConfig.Permissions cfg = new GuardianConfig.Permissions(false, 2, Map.of());
        PermissionResolver r = new PermissionResolver(cfg, uuid -> 2);

        assertThat(r.has(ALICE, "vonixguardian.command.rollback")).isFalse();
        assertThat(r.has(ALICE, "vonixguardian.command.lookup")).isTrue();
    }

    @Test
    void legacyCommandStringHonorsCanonicalPerNodeOverride() {
        Map<String, Integer> overrides = Map.of(PermissionNode.LOOKUP.node(), 4);
        GuardianConfig.Permissions cfg = new GuardianConfig.Permissions(false, 2, overrides);
        PermissionResolver r = new PermissionResolver(cfg, uuid -> 2);

        assertThat(r.has(ALICE, "vonixguardian.command.lookup")).isFalse();
    }

    @Test
    void perNodeOpLevel_nullMapTreatedAsEmpty() {
        GuardianConfig.Permissions cfg = new GuardianConfig.Permissions(false, 0, null);
        PermissionResolver r = new PermissionResolver(cfg, uuid -> 0);
        assertThat(r.perNodeOpLevel(PermissionNode.LOOKUP))
                .isEqualTo(PermissionNode.LOOKUP.defaultOpLevel());
    }

    @Test
    void perNodeOpLevel_outOfRangeOverrideFallsBackToDefault() {
        Map<String, Integer> overrides = Map.of(PermissionNode.LOOKUP.node(), 99);
        GuardianConfig.Permissions cfg = new GuardianConfig.Permissions(false, 0, overrides);
        PermissionResolver r = new PermissionResolver(cfg, uuid -> 0);
        assertThat(r.perNodeOpLevel(PermissionNode.LOOKUP))
                .isEqualTo(PermissionNode.LOOKUP.defaultOpLevel());
    }
}

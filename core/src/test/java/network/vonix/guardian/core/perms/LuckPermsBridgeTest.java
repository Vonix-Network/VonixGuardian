package network.vonix.guardian.core.perms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import network.vonix.guardian.core.perms.LuckPermsBridge.TristateResult;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link LuckPermsBridge}.
 *
 * <p>LuckPerms is intentionally not on the test classpath, so {@link LuckPermsBridge#isAvailable()}
 * is expected to return {@code false} and {@link LuckPermsBridge#checkPermission} is expected to
 * throw {@link ClassNotFoundException}. We additionally exercise the enum-name mapping helper
 * directly with a stand-in enum.
 */
class LuckPermsBridgeTest {

    /** Stand-in enum that mirrors the names of {@code net.luckperms.api.util.Tristate}. */
    enum FakeTristate {
        TRUE,
        FALSE,
        UNDEFINED
    }

    @Test
    void isAvailable_returnsFalse_whenLuckPermsNotOnClasspath() {
        assertThat(LuckPermsBridge.isAvailable()).isFalse();
    }

    @Test
    void checkPermission_throwsClassNotFound_whenLuckPermsAbsent() {
        UUID uuid = UUID.randomUUID();
        assertThatThrownBy(() -> LuckPermsBridge.checkPermission(uuid, "guardian.lookup"))
                .isInstanceOf(ClassNotFoundException.class);
    }

    @Test
    void checkPermission_returnsUndefined_forNullOrEmptyInputs() throws Exception {
        // Null / empty short-circuit before any reflection — they must NOT throw CNF.
        assertThat(LuckPermsBridge.checkPermission(null, "node")).isEqualTo(TristateResult.UNDEFINED);
        assertThat(LuckPermsBridge.checkPermission(UUID.randomUUID(), null))
                .isEqualTo(TristateResult.UNDEFINED);
        assertThat(LuckPermsBridge.checkPermission(UUID.randomUUID(), ""))
                .isEqualTo(TristateResult.UNDEFINED);
    }

    @Test
    void mapTristate_mapsByEnumName() {
        assertThat(LuckPermsBridge.mapTristate(FakeTristate.TRUE)).isEqualTo(TristateResult.TRUE);
        assertThat(LuckPermsBridge.mapTristate(FakeTristate.FALSE)).isEqualTo(TristateResult.FALSE);
        assertThat(LuckPermsBridge.mapTristate(FakeTristate.UNDEFINED))
                .isEqualTo(TristateResult.UNDEFINED);
    }

    @Test
    void mapTristate_handlesNull() {
        assertThat(LuckPermsBridge.mapTristate(null)).isEqualTo(TristateResult.UNDEFINED);
    }

    @Test
    void mapTristate_handlesNonEnumViaToString() {
        Object stringly =
                new Object() {
                    @Override
                    public String toString() {
                        return "TRUE";
                    }
                };
        assertThat(LuckPermsBridge.mapTristate(stringly)).isEqualTo(TristateResult.TRUE);

        Object falseish =
                new Object() {
                    @Override
                    public String toString() {
                        return "false";
                    }
                };
        assertThat(LuckPermsBridge.mapTristate(falseish)).isEqualTo(TristateResult.FALSE);

        Object weird =
                new Object() {
                    @Override
                    public String toString() {
                        return "MAYBE";
                    }
                };
        assertThat(LuckPermsBridge.mapTristate(weird)).isEqualTo(TristateResult.UNDEFINED);
    }
}

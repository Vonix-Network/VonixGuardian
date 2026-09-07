package network.vonix.guardian.core.event;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Regression guard for the Fabric 1.20.1 HopperBlockEntityMixin callback
 * descriptor. The vanilla ejectItems target passes a Container parameter;
 * using HopperBlockEntity makes Fabric Mixin reject the callback at boot.
 */
class FabricHopperEjectDescriptorStructuralTest {

    @Test
    void fabric1201EjectCallbacksAndBridgeUseContainer() throws Exception {
        Path root = repoRoot();
        assumeTrue(root != null, "repo root not resolvable");

        Path mixin = root.resolve("mc-1.20.1/fabric/src/main/java/network/vonix/guardian/mc/v1_20_1/fabric/mixin/HopperBlockEntityMixin.java");
        Path bridge = root.resolve("mc-1.20.1/fabric/src/main/java/network/vonix/guardian/mc/v1_20_1/fabric/FabricMixinBridge.java");
        assumeTrue(Files.exists(mixin), "Fabric 1.20.1 hopper mixin missing");
        assumeTrue(Files.exists(bridge), "Fabric 1.20.1 mixin bridge missing");

        String mixinText = Files.readString(mixin);
        assertThat(mixinText)
                .contains("vg$beforeEjectItems(Level level, BlockPos pos, BlockState state, Container hopper,")
                .contains("vg$onEjectItems(Level level, BlockPos pos, BlockState state, Container hopper,")
                .doesNotContain("ejectItems(Level level, BlockPos pos, BlockState state, HopperBlockEntity hopper");

        String bridgeText = Files.readString(bridge);
        assertThat(bridgeText)
                .contains("hopperEjectBegin(Level level, BlockPos pos, Container hopper)")
                .contains("hopperEjectCommit(Level level, BlockPos pos, Container hopper)")
                .contains("emitHopperDiff(Level level, BlockPos hopperPos, Container hopper,");
    }

    private static Path repoRoot() {
        Path here = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (Path p = here; p != null; p = p.getParent()) {
            if (Files.exists(p.resolve("settings.gradle")) && Files.exists(p.resolve("mc-1.20.1/fabric"))) {
                return p;
            }
        }
        return null;
    }
}

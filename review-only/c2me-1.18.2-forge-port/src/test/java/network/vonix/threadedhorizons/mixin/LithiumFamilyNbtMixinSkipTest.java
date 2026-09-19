package network.vonix.threadedhorizons.mixin;

import network.vonix.threadedhorizons.common.config.ThreadedHorizonsConfig.GeneralOptimizationsConfig.AutoSaveConfig.Mode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LithiumFamilyNbtMixinSkipTest {

    private static final String COMPOUND =
            "network.vonix.threadedhorizons.mixin.optimization.reduce_allocs.MixinNbtCompound";
    private static final String COMPOUND1 =
            "network.vonix.threadedhorizons.mixin.optimization.reduce_allocs.MixinNbtCompound1";
    private static final String TARGET = "net.minecraft.nbt.CompoundTag";

    @Test
    void nbtRedirectMixinsApplyWithoutLithiumFamilyMods() {
        ThreadedHorizonsMixinPlugin plugin = new ThreadedHorizonsMixinPlugin();
        assertTrue(plugin.evaluate(TARGET, COMPOUND, true, Mode.ENHANCED, false));
        assertTrue(plugin.evaluate(TARGET, COMPOUND1, true, Mode.ENHANCED, false));
    }

    @Test
    void nbtRedirectMixinsSkipWhenCanaryIsPresent() {
        ThreadedHorizonsMixinPlugin plugin = new ThreadedHorizonsMixinPlugin();
        assertFalse(plugin.evaluate(TARGET, COMPOUND, true, Mode.ENHANCED, true),
                "Canary redirects CompoundTag HashMap allocation; C2ME must yield");
        assertFalse(plugin.evaluate(TARGET, COMPOUND1, true, Mode.ENHANCED, true));
    }
}

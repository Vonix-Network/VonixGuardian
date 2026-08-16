package network.vonix.threadedhorizons.client.mixin.uncapvd;

import network.vonix.threadedhorizons.common.config.ThreadedHorizonsConfig;
import org.spongepowered.asm.mixin.Dynamic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

@Pseudo
@Mixin(targets = "me.jellysquid.mods.sodium.client.gui.SodiumGameOptionPages")
public class MixinSodiumGameOptionPages {

    @Dynamic
    @ModifyConstant(method = "lambda$general$0", constant = @Constant(intValue = 32), remap = false)
    private static int modifyMaxViewDistance(int value) {
        return ThreadedHorizonsConfig.clientSideConfig.modifyMaxVDConfig.maxViewDistance;
    }

}

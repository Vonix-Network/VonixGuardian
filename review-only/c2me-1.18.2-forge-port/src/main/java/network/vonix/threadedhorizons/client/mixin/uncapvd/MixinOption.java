package network.vonix.threadedhorizons.client.mixin.uncapvd;

import network.vonix.threadedhorizons.common.config.ThreadedHorizonsConfig;
import net.minecraft.client.ProgressOption;
import net.minecraft.client.Option;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Option.class)
public class MixinOption {

    @Shadow @Final public static ProgressOption RENDER_DISTANCE;

    private static final int THRenderDistance = ThreadedHorizonsConfig.clientSideConfig.modifyMaxVDConfig.maxViewDistance;

    @Inject(method = "<clinit>", at = @At(value = "TAIL"))
    private static void modifyMaxViewDistance(CallbackInfo ci) {
        if (RENDER_DISTANCE.getMaxValue() < THRenderDistance) {
            RENDER_DISTANCE.setMaxValue(THRenderDistance);
        }
    }
}

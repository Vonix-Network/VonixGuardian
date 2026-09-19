package network.vonix.threadedhorizons.client.mixin.uncapvd;

import net.minecraft.client.ProgressOption;
import net.minecraft.client.Options;
import net.minecraft.client.Option;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(Options.class)
public class MixinGameOptions {

    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/ProgressOption;setMaxValue(F)V"))
    private void redirectSetMaxVD(ProgressOption doubleOption, float max) {
        if (doubleOption == Option.RENDER_DISTANCE) return;
        doubleOption.setMaxValue(max);
    }

}

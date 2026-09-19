package network.vonix.threadedhorizons.mixin.fixes.worldgen.threading.math;

import com.mojang.math.Transformation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Transformation.class)
public abstract class MixinAffineTransformation {

    @Shadow protected abstract void ensureDecomposed();

    @Inject(method = "<init>*", at = @At("RETURN"))
    private void onInit(CallbackInfo info) {
        this.ensureDecomposed(); // run init early
    }

}

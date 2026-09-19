package network.vonix.threadedhorizons.mixin.fixes.asynccatchers;

import net.minecraft.server.level.ServerChunkCache;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerChunkCache.class)
public class MixinServerChunkManager {

    @Shadow @Final private Thread mainThread;

    @Inject(method = "tick(Ljava/util/function/BooleanSupplier;Z)V", at = @At("HEAD"))
    private void onTick(CallbackInfo ci) {
        if (Thread.currentThread() != this.mainThread) throw new IllegalStateException("Async ticking server chunk manager");
    }

}

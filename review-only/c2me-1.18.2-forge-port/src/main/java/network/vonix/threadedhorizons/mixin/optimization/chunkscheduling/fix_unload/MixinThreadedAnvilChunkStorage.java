package network.vonix.threadedhorizons.mixin.optimization.chunkscheduling.fix_unload;

import network.vonix.threadedhorizons.common.structs.LongHashSet;
import network.vonix.threadedhorizons.common.util.ShouldKeepTickingUtils;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.util.thread.BlockableEventLoop;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BooleanSupplier;

@Mixin(ChunkMap.class)
public abstract class MixinThreadedAnvilChunkStorage {

    @Shadow @Final private BlockableEventLoop<Runnable> mainThreadExecutor;

    @Shadow protected abstract void processUnloads(BooleanSupplier shouldKeepTicking);

    @Mutable
    @Shadow @Final private LongSet toDrop;

    @Redirect(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/ai/village/poi/PoiManager;tick(Ljava/util/function/BooleanSupplier;)V"))
    private void redirectTickPointOfInterestStorageTick(PoiManager poiManager, BooleanSupplier shouldKeepTicking) {
        poiManager.tick(ShouldKeepTickingUtils.minimumTicks(shouldKeepTicking, 32));
    }

    @Redirect(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ChunkMap;processUnloads(Ljava/util/function/BooleanSupplier;)V"))
    private void redirectTickUnloadChunks(ChunkMap chunkMap, BooleanSupplier shouldKeepTicking) {
        this.processUnloads(ShouldKeepTickingUtils.minimumTicks(shouldKeepTicking, 32));
    }

    @Inject(method = "<init>", at = @At("RETURN"))
    private void onInit(CallbackInfo info) {
        this.toDrop = new LongHashSet();
    }

}

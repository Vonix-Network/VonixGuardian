package network.vonix.threadedhorizons.mixin.fixes.chunkio.threading;

import network.vonix.threadedhorizons.mixin.access.IThreadedAnvilChunkStorage;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.Executor;

@Mixin(ChunkHolder.class)
public class MixinChunkHolder {

    @Inject(method = "updateFutures", at = @At("HEAD"))
    private void beforeTick(ChunkMap chunkStorage, Executor executor, CallbackInfo ci) {
        ((IThreadedAnvilChunkStorage) chunkStorage).invokeUpdateHolderMap();
    }

}

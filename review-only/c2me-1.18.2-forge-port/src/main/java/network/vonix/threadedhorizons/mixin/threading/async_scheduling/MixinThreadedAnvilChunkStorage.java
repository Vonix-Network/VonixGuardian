package network.vonix.threadedhorizons.mixin.threading.async_scheduling;

import network.vonix.threadedhorizons.common.GlobalExecutors;
import com.mojang.datafixers.util.Either;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

@Mixin(ChunkMap.class)
public abstract class MixinThreadedAnvilChunkStorage {

    @Shadow @Final private ServerLevel level;

    @Shadow protected abstract CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>> schedule(ChunkHolder holder, ChunkStatus requiredStatus);

    @Shadow private volatile Long2ObjectLinkedOpenHashMap<ChunkHolder> visibleChunkMap;

    @Inject(method = "schedule", at = @At(value = "HEAD"), cancellable = true)
    private void beforeUpgradeChunk(ChunkHolder holder, ChunkStatus requiredStatus, CallbackInfoReturnable<CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>>> cir) {
        if (this.level.getServer().getRunningThread() == Thread.currentThread()) {
            cir.setReturnValue(
                    CompletableFuture.supplyAsync(() -> this.schedule(holder, requiredStatus), GlobalExecutors.executor).thenCompose(Function.identity())
            );
        }
    }

    @SuppressWarnings("InvalidInjectorMethodSignature")
    @ModifyVariable(method = "scheduleChunkGeneration", at = @At(value = "INVOKE_ASSIGN", target = "Lnet/minecraft/server/level/ChunkMap;getChunkRangeFuture(Lnet/minecraft/world/level/ChunkPos;ILjava/util/function/IntFunction;)Ljava/util/concurrent/CompletableFuture;"))
    private CompletableFuture<Either<List<ChunkAccess>, ChunkHolder.ChunkLoadingFailure>> asyncUpgradeChunkTask(CompletableFuture<Either<List<ChunkAccess>, ChunkHolder.ChunkLoadingFailure>> value) {
        return value.thenApplyAsync(Function.identity(), runnable -> {
            if (this.level.getServer().getRunningThread() == Thread.currentThread()) {
                GlobalExecutors.executor.execute(runnable);
            } else {
                runnable.run();
            }
        });
    }

    @Redirect(method = "getChunkRangeFuture", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ChunkMap;getUpdatingChunkIfPresent(J)Lnet/minecraft/server/level/ChunkHolder;"))
    private ChunkHolder redirectGetChunkHolder(ChunkMap instance, long pos) {
        return this.visibleChunkMap.get(pos); // thread-safe
    }

}

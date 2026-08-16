package network.vonix.threadedhorizons.mixin.threading.worldgen;

import network.vonix.threadedhorizons.common.threading.scheduler.SchedulerThread;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.Function;

@Mixin(ChunkMap.class)
public class MixinThreadedAnvilChunkStorage {

    /**
     * Official 1.18.2 {@code scheduleChunkGeneration} builds an {@link Executor} from
     * synthetic {@code lambda$scheduleChunkGeneration$19} (production SRG {@code m_203098_})
     * that only hops through {@code worldgenMailbox}. Mixin AP cannot write a remappable
     * {@code @Overwrite} owner for that synthetic. Redirect the single remappable
     * {@code thenComposeAsync} that consumes that executor and run the compose immediately,
     * which is the same scheduling-overhead reduction as {@code runnable.run()}.
     */
    @Redirect(
            method = "scheduleChunkGeneration(Lnet/minecraft/server/level/ChunkHolder;Lnet/minecraft/world/level/chunk/ChunkStatus;)Ljava/util/concurrent/CompletableFuture;",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/util/concurrent/CompletableFuture;thenComposeAsync(Ljava/util/function/Function;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;"
            )
    )
    private <T, U> CompletableFuture<U> runWorldGenComposeImmediately(
            CompletableFuture<T> future,
            Function<? super T, ? extends CompletionStage<U>> fn,
            Executor executor) {
        return future.thenComposeAsync(fn, Runnable::run);
    }

    @Inject(method = "protoChunkToFullChunk", at = @At("RETURN"))
    private void releaseWorldGenPipelineOnFullChunk(ChunkHolder holder, CallbackInfoReturnable<?> cir) {
        if (holder != null) {
            SchedulerThread.INSTANCE.leaveChunkPipeline(holder.getPos());
        }
    }

}

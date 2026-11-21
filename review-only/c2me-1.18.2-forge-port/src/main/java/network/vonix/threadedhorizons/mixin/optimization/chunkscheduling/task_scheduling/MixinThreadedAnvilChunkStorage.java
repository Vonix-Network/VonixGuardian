package network.vonix.threadedhorizons.mixin.optimization.chunkscheduling.task_scheduling;

import network.vonix.threadedhorizons.common.optimization.chunkscheduling.IThreadedAnvilChunkStorage;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.TicketType;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.Util;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.util.thread.BlockableEventLoop;
import net.minecraft.world.level.chunk.ChunkStatus;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;

@Mixin(ChunkMap.class)
public class MixinThreadedAnvilChunkStorage implements IThreadedAnvilChunkStorage {

    @Shadow
    @Final
    private ServerLevel level;
    @Shadow @Final private BlockableEventLoop<Runnable> mainThreadExecutor;

    @Shadow @Final private ChunkMap.DistanceManager distanceManager;
    private final Executor mainInvokingExecutor = runnable -> {
        if (this.level.getServer().isSameThread()) {
            runnable.run();
        } else {
            this.mainThreadExecutor.execute(runnable);
        }
    };

    @Override
    public Executor getMainInvokingExecutor() {
        return mainInvokingExecutor;
    }

    /**
     * reduce scheduling overhead with mainInvokingExecutor
     */
    @Redirect(method = "prepareEntityTickingChunk", at = @At(value = "INVOKE", target = "Ljava/util/concurrent/CompletableFuture;thenApplyAsync(Ljava/util/function/Function;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;"))
    private <U, T> CompletableFuture<U> redirectMainThreadExecutor1(CompletableFuture<T> completableFuture, Function<? super T, ? extends U> fn, Executor executor) {
        return completableFuture.thenApplyAsync(fn, this.mainInvokingExecutor);
    }

    /**
     * @author ishland
     * @reason reduce scheduling overhead with mainInvokingExecutor
     */
    @Overwrite
    public void releaseLightTicket(ChunkPos pos) {
        this.mainInvokingExecutor.execute(Util.name(() -> {
            this.distanceManager.removeTicket(TicketType.LIGHT, pos, 33 + ChunkStatus.getDistance(ChunkStatus.LIGHT), pos);
        }, () -> {
            return "release light ticket " + pos;
        }));
    }

}

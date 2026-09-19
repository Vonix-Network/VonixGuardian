package network.vonix.threadedhorizons.mixin.threading.worldgen;

import network.vonix.threadedhorizons.common.config.ThreadedHorizonsConfig;
import network.vonix.threadedhorizons.common.threading.scheduler.PriorityUtils;
import network.vonix.threadedhorizons.common.threading.scheduler.SchedulerThread;
import network.vonix.threadedhorizons.common.threading.worldgen.ChunkStatusUtils;
import network.vonix.threadedhorizons.common.threading.worldgen.IChunkStatus;
import network.vonix.threadedhorizons.common.threading.worldgen.IWorldGenLockable;
import com.mojang.datafixers.util.Either;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureManager;
import net.minecraft.util.profiling.jfr.callback.ProfiledDuration;
import net.minecraft.util.profiling.jfr.JvmProfiler;
import net.minecraft.core.Registry;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.ChunkGenerator;
import org.spongepowered.asm.mixin.Dynamic;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.function.Supplier;

@Mixin(ChunkStatus.class)
public abstract class MixinChunkStatus implements IChunkStatus {

    @Shadow
    @Final
    private ChunkStatus.GenerationTask generationTask;

    @Shadow
    @Final
    private int range;

    @Shadow @Final private String name;
    private int reducedTaskRadius = -1;

    public void calculateReducedTaskRadius() {
        if (this.range == 0) {
            this.reducedTaskRadius = 0;
        } else {
            for (int i = 0; i <= this.range; i++) {
                final ChunkStatus status = ChunkStatus.getStatusAroundFullChunk(ChunkStatus.getDistance((ChunkStatus) (Object) this) + i);
                if (status == ChunkStatus.STRUCTURE_STARTS) {
                    this.reducedTaskRadius = Math.min(this.range, i);
                    break;
                }
            }
        }
        //noinspection ConstantConditions
        if ((Object) this == ChunkStatus.LIGHT) {
            this.reducedTaskRadius = 1;
        }
        System.out.printf("%s task radius: %d -> %d%n", this, this.range, this.reducedTaskRadius);
    }

    @Override
    public int getReducedTaskRadius() {
        return this.reducedTaskRadius;
    }

    @Dynamic
    @Inject(method = "<clinit>", at = @At("RETURN"))
    private static void onCLInit(CallbackInfo info) {
        for (ChunkStatus chunkStatus : Registry.CHUNK_STATUS) {
            ((IChunkStatus) chunkStatus).calculateReducedTaskRadius();
        }
    }

    /**
     * @author ishland
     * @reason take over generation
     */
    @Overwrite
    public CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>> generate(Executor executor, ServerLevel world, ChunkGenerator chunkGenerator, StructureManager structureManager, ThreadedLevelLightEngine lightingProvider, Function<ChunkAccess, CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>>> function, List<ChunkAccess> list, boolean bl) {
        final ChunkAccess targetChunk = list.get(list.size() / 2);

        ProfiledDuration finishable = JvmProfiler.INSTANCE.onChunkGenerate(targetChunk.getPos(), world.dimension(), this.name);

        final Supplier<CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>>> generationTask = () -> {
            SchedulerThread.enterNestedGeneration();
            try {
                return this.generationTask.doWork((ChunkStatus) (Object) this, executor, world, chunkGenerator, structureManager, lightingProvider, function, list, targetChunk, bl);
            } finally {
                SchedulerThread.leaveNestedGeneration();
            }
        };

        final CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>> completableFuture;
        if (targetChunk.getStatus().isOrAfter((ChunkStatus) (Object) this)) {
            completableFuture = generationTask.get();
            if ((Object) this == ChunkStatus.FULL) {
                SchedulerThread.INSTANCE.leaveChunkPipeline(targetChunk.getPos());
            }
        } else {
            int lockRadius = ThreadedHorizonsConfig.threadedWorldGenConfig.reduceLockRadius && this.reducedTaskRadius != -1 ? this.reducedTaskRadius : this.range;
            //noinspection ConstantConditions
            completableFuture = ChunkStatusUtils.runChunkGenWithLock(targetChunk.getPos(), lockRadius, PriorityUtils.getChunkPriority(world, targetChunk), ((IWorldGenLockable) world).getWorldGenChunkLock(), () ->
                    ChunkStatusUtils.getThreadingType((ChunkStatus) (Object) this).runTask(((IWorldGenLockable) world).getWorldGenSingleThreadedLock(), generationTask), (ChunkStatus) (Object) this);
        }

        completableFuture.exceptionally(throwable -> {
            throwable.printStackTrace();
            return null;
        });
        return finishable != null ? completableFuture.thenApply(either -> {
            finishable.finish();
            return either;
        }) : completableFuture;
    }

}

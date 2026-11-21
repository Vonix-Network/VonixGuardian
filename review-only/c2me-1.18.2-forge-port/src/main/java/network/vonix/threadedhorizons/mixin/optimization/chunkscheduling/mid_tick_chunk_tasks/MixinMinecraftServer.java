package network.vonix.threadedhorizons.mixin.optimization.chunkscheduling.mid_tick_chunk_tasks;

import network.vonix.threadedhorizons.common.optimization.chunkscheduling.ServerMidTickTask;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(MinecraftServer.class)
public abstract class MixinMinecraftServer implements ServerMidTickTask {

    @Shadow public abstract Iterable<ServerLevel> getAllLevels();

    @Shadow @Final private Thread serverThread;
    private static final long minMidTickTaskInterval = 100_000L; // 100us
    private long lastRun = System.nanoTime();

    public void executeTasksMidTick() {
        if (this.serverThread != Thread.currentThread()) return;
        if (System.nanoTime() - lastRun < minMidTickTaskInterval) return;
        for (ServerLevel world : this.getAllLevels()) {
            world.getChunkSource().mainThreadProcessor.pollTask();
        }
        lastRun = System.nanoTime();
    }

}

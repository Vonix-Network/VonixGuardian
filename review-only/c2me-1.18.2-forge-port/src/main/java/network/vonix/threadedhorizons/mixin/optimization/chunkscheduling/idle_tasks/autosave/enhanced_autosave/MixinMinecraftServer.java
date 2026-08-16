package network.vonix.threadedhorizons.mixin.optimization.chunkscheduling.idle_tasks.autosave.enhanced_autosave;

import network.vonix.threadedhorizons.common.optimization.chunkscheduling.idle_tasks.IThreadedAnvilChunkStorage;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.thread.ReentrantBlockableEventLoop;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(MinecraftServer.class)
public abstract class MixinMinecraftServer extends ReentrantBlockableEventLoop<TickTask> {

    @Shadow protected abstract boolean haveTime();

    @Shadow public abstract Iterable<ServerLevel> getAllLevels();

    public MixinMinecraftServer(String string) {
        super(string);
    }

    /**
     * @author ishland
     * @reason improve task execution when waiting for next tick
     */
    @Overwrite
    public boolean pollTask() {
        if (super.pollTask()) {
            return true;
        } else {
            boolean hasWork = false;
            if (this.haveTime()) {
                for(ServerLevel serverWorld : this.getAllLevels()) {
                    if (serverWorld.getChunkSource().pollTask()) hasWork = true;
                }
            }

            if (!hasWork && this.haveTime()) {
                for (ServerLevel serverWorld : this.getAllLevels()) {
                    if (this.haveTime()) {
                        hasWork = ((IThreadedAnvilChunkStorage) serverWorld.getChunkSource().chunkMap).runOneChunkAutoSave();
                    }
                }
            }

            return hasWork;
        }
    }

}

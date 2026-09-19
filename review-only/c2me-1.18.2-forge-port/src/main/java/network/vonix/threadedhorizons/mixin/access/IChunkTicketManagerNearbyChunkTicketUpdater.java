package network.vonix.threadedhorizons.mixin.access;

import it.unimi.dsi.fastutil.longs.Long2IntMap;
import net.minecraft.server.level.DistanceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(DistanceManager.PlayerTicketTracker.class)
public interface IChunkTicketManagerNearbyChunkTicketUpdater {

    @Accessor("queueLevels")
    Long2IntMap getDistances();

}

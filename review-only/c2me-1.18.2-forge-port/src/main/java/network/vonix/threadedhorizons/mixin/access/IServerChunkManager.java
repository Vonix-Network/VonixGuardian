package network.vonix.threadedhorizons.mixin.access;

import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.ServerChunkCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ServerChunkCache.class)
public interface IServerChunkManager {

    @Accessor
    DistanceManager getDistanceManager();

}

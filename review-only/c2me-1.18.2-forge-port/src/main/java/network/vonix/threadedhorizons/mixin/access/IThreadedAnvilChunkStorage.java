package network.vonix.threadedhorizons.mixin.access;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ChunkMap.class)
public interface IThreadedAnvilChunkStorage {

    @Accessor("level")
    ServerLevel getWorld();

    @Invoker("promoteChunkMap")
    boolean invokeUpdateHolderMap();

    @Accessor("updatingChunkMap")
    Long2ObjectLinkedOpenHashMap<ChunkHolder> getChunkHolders();

    @Invoker("saveChunkIfNeeded")
    boolean invokeSave(ChunkHolder chunkHolder);

}

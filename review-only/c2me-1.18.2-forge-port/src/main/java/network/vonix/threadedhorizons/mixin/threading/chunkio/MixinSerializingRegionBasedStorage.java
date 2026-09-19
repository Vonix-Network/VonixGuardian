package network.vonix.threadedhorizons.mixin.threading.chunkio;

import network.vonix.threadedhorizons.common.threading.chunkio.ISerializingRegionBasedStorage;
import com.mojang.serialization.DynamicOps;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.SectionStorage;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(SectionStorage.class)
public abstract class MixinSerializingRegionBasedStorage implements ISerializingRegionBasedStorage {

    @Shadow
    protected abstract <T> void readColumn(ChunkPos pos, DynamicOps<T> dynamicOps, @Nullable T data);

    @Override
    public void update(ChunkPos pos, CompoundTag tag) {
        this.readColumn(pos, NbtOps.INSTANCE, tag);
    }

}

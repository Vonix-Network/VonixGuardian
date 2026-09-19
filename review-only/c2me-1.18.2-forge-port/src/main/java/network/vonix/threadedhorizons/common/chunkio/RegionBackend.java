package network.vonix.threadedhorizons.common.chunkio;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.StreamTagVisitor;
import net.minecraft.world.level.ChunkPos;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;

public interface RegionBackend extends AutoCloseable {

    @Nullable
    CompoundTag read(ChunkPos pos) throws IOException;

    void write(ChunkPos pos, CompoundTag nbt) throws IOException;

    void clear(ChunkPos pos) throws IOException;

    void scan(ChunkPos pos, StreamTagVisitor visitor) throws IOException;

    void flush() throws IOException;

    @Override
    void close() throws IOException;
}

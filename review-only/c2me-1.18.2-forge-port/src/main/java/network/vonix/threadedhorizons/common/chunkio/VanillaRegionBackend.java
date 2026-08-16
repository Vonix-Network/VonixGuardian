package network.vonix.threadedhorizons.common.chunkio;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.StreamTagVisitor;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import org.jetbrains.annotations.Nullable;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Region I/O through public {@link RegionFile} stream APIs so framing stays
 * in the 1.18.2 RegionFile implementation.
 */
public final class VanillaRegionBackend implements RegionBackend {

    private final RegionFileStorage storage;

    public VanillaRegionBackend(Path directory, boolean dsync) {
        this.storage = new RegionFileStorage(directory, dsync);
    }

    @Override
    public @Nullable CompoundTag read(ChunkPos pos) throws IOException {
        RegionFile regionFile = this.storage.getRegionFile(pos);
        DataInputStream input = regionFile.getChunkDataInputStream(pos);
        if (input == null) {
            return null;
        }
        try (DataInputStream stream = input) {
            return NbtIo.read(stream);
        }
    }

    @Override
    public void write(ChunkPos pos, CompoundTag nbt) throws IOException {
        RegionFile regionFile = this.storage.getRegionFile(pos);
        try (DataOutputStream output = regionFile.getChunkDataOutputStream(pos)) {
            NbtIo.write(nbt, output);
        }
    }

    @Override
    public void clear(ChunkPos pos) throws IOException {
        RegionFile regionFile = this.storage.getRegionFile(pos);
        regionFile.clear(pos);
    }

    @Override
    public void scan(ChunkPos pos, StreamTagVisitor visitor) throws IOException {
        RegionFile regionFile = this.storage.getRegionFile(pos);
        DataInputStream input = regionFile.getChunkDataInputStream(pos);
        if (input == null) {
            return;
        }
        try (DataInputStream stream = input) {
            NbtIo.parse(stream, visitor);
        }
    }

    @Override
    public void flush() throws IOException {
        this.storage.flush();
    }

    @Override
    public void close() throws IOException {
        this.storage.close();
    }
}

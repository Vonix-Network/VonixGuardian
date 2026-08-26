package network.vonix.threadedhorizons.common.chunkio;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.StreamTagVisitor;
import net.minecraft.world.level.ChunkPos;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryRegionBackend implements RegionBackend {

    private final ConcurrentHashMap<Long, CompoundTag> disk = new ConcurrentHashMap<>();
    private volatile boolean closed;

    @Override
    public @Nullable CompoundTag read(ChunkPos pos) throws IOException {
        ensureOpen();
        CompoundTag tag = this.disk.get(pos.toLong());
        return tag == null ? null : tag.copy();
    }

    @Override
    public void write(ChunkPos pos, CompoundTag nbt) throws IOException {
        ensureOpen();
        this.disk.put(pos.toLong(), nbt.copy());
    }

    @Override
    public void clear(ChunkPos pos) throws IOException {
        ensureOpen();
        this.disk.remove(pos.toLong());
    }

    @Override
    public void scan(ChunkPos pos, StreamTagVisitor visitor) throws IOException {
        CompoundTag tag = read(pos);
        if (tag != null) {
            tag.accept(visitor);
        }
    }

    @Override
    public void flush() throws IOException {
        ensureOpen();
    }

    @Override
    public void close() throws IOException {
        this.closed = true;
    }

    public CompoundTag diskGet(long pos) {
        CompoundTag tag = this.disk.get(pos);
        return tag == null ? null : tag.copy();
    }

    private void ensureOpen() throws IOException {
        if (this.closed) {
            throw new StorageClosedException("backend closed");
        }
    }
}

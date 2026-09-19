package network.vonix.threadedhorizons.common.chunkio;

import com.google.common.base.Preconditions;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.StreamTagVisitor;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.IOWorker;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public class ThreadedHorizonsStorageVanillaInterface extends IOWorker {

    private final ThreadedHorizonsStorageThread backend;

    public ThreadedHorizonsStorageVanillaInterface(Path directory, boolean dsync, String name) {
        super(null, dsync, name);
        this.backend = new ThreadedHorizonsStorageThread(directory, dsync, name);
    }

    ThreadedHorizonsStorageVanillaInterface(ThreadedHorizonsStorageThread backend) {
        super(null, false, backend.getName());
        this.backend = backend;
    }

    @Override
    public CompletableFuture<Void> store(ChunkPos pos, @Nullable CompoundTag nbt) {
        return this.backend.store(pos.toLong(), nbt);
    }

    @Nullable
    @Override
    public CompoundTag load(ChunkPos pos) {
        try {
            return this.backend.getChunkData(pos.toLong(), null).join();
        } catch (CompletionException exception) {
            Throwable cause = exception.getCause() != null ? exception.getCause() : exception;
            throw new CompletionException(cause);
        }
    }

    @Override
    protected CompletableFuture<CompoundTag> loadAsync(ChunkPos pos) {
        return this.backend.getChunkData(pos.toLong(), null);
    }

    @Override
    public CompletableFuture<Void> synchronize(boolean sync) {
        return this.backend.flush(true);
    }

    @Override
    public CompletableFuture<Void> scanChunk(ChunkPos pos, StreamTagVisitor scanner) {
        Preconditions.checkNotNull(scanner, "scanner");
        return this.backend.getChunkData(pos.toLong(), scanner).thenApply(unused -> null);
    }

    @Override
    public void close() throws IOException {
        try {
            this.backend.close().join();
        } catch (CompletionException exception) {
            Throwable cause = exception.getCause() != null ? exception.getCause() : exception;
            if (cause instanceof IOException ioException) {
                throw ioException;
            }
            throw new IOException(cause);
        }
    }
}

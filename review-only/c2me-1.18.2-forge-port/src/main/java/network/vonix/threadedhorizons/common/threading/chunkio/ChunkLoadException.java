package network.vonix.threadedhorizons.common.threading.chunkio;

import net.minecraft.world.level.ChunkPos;

public final class ChunkLoadException extends RuntimeException {

    public enum Kind {
        MISSING_STATUS,
        CORRUPT,
        DATA_FIX,
        DESERIALIZE,
        POI
    }

    public final ChunkPos pos;
    public final Kind kind;

    public ChunkLoadException(ChunkPos pos, Kind kind, String message) {
        super(message);
        this.pos = pos;
        this.kind = kind;
    }

    public ChunkLoadException(ChunkPos pos, Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.pos = pos;
        this.kind = kind;
    }
}

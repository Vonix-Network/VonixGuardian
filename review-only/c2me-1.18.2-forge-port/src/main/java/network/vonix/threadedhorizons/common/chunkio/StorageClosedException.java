package network.vonix.threadedhorizons.common.chunkio;

import java.io.IOException;

public final class StorageClosedException extends IOException {

    public StorageClosedException(String message) {
        super(message);
    }

    public StorageClosedException(String message, Throwable cause) {
        super(message, cause);
    }
}

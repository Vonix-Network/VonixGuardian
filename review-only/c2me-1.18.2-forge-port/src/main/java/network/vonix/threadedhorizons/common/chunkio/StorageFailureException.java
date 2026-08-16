package network.vonix.threadedhorizons.common.chunkio;

import java.io.IOException;

public final class StorageFailureException extends IOException {

    private final StorageFailureClass failureClass;

    public StorageFailureException(StorageFailureClass failureClass, String message, Throwable cause) {
        super(message, cause);
        this.failureClass = failureClass;
    }

    public StorageFailureClass failureClass() {
        return failureClass;
    }

    public static StorageFailureClass classify(Throwable throwable) {
        Throwable root = throwable;
        while (root.getCause() != null && root.getCause() != root) {
            if (root instanceof StorageClosedException) {
                return StorageFailureClass.PERMANENT;
            }
            root = root.getCause();
        }
        if (root instanceof StorageClosedException || throwable instanceof StorageClosedException) {
            return StorageFailureClass.PERMANENT;
        }
        if (root instanceof StorageFailureException failure) {
            return failure.failureClass();
        }
        if (root instanceof OutOfMemoryError || root instanceof LinkageError) {
            return StorageFailureClass.PERMANENT;
        }
        if (root instanceof RuntimeException && !(root instanceof IllegalStateException) && isSerializationFailure(root)) {
            return StorageFailureClass.PERMANENT;
        }
        return StorageFailureClass.RETRYABLE;
    }

    private static boolean isSerializationFailure(Throwable throwable) {
        String name = throwable.getClass().getName();
        return name.contains("Nbt") || name.contains("Tag") || name.contains("Codec");
    }
}

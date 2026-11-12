package network.vonix.guardian.core.query;

/**
 * {@code r:#worldedit} could not be turned into a region. The message is
 * what the operator should read. Callers must not treat this as an empty
 * result.
 */
public final class WorldEditQueryException extends RuntimeException {

    public WorldEditQueryException(String message) {
        super(message);
    }
}

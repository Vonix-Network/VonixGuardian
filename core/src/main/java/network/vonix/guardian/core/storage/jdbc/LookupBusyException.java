package network.vonix.guardian.core.storage.jdbc;

/**
 * Fail-closed signal for an operator lookup that could not obtain a bounded
 * read permit. Callers should report a retryable busy response rather than
 * waiting indefinitely and consuming an executor thread.
 */
public final class LookupBusyException extends RuntimeException {

    public LookupBusyException(String message) {
        super(message);
    }

    public LookupBusyException(String message, Throwable cause) {
        super(message, cause);
    }
}
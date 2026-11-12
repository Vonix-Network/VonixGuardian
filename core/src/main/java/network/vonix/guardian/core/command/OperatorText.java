package network.vonix.guardian.core.command;

/**
 * Turns a failure into a sentence an operator can read.
 * {@link Throwable#getMessage()} is often null, and printing that produced
 * "Lookup error: null".
 */
public final class OperatorText {

    private OperatorText() {}

    public static String detail(Throwable failure) {
        if (failure == null) {
            return "unknown error";
        }
        String message = failure.getMessage();
        if (message == null || message.isBlank() || "null".equals(message)) {
            String name = failure.getClass().getSimpleName();
            return name == null || name.isBlank() ? "unknown error" : name;
        }
        return message;
    }
}

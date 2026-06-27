package network.vonix.guardian.core.query;

/**
 * Thrown when {@link QueryParser#parse(String,
 * network.vonix.guardian.core.query.QueryParser.QueryParseContext)} encounters
 * a malformed token.
 *
 * <p>The message is intended to be shown directly to the player (it includes
 * the bad token and, where possible, a suggestion of valid alternatives), so
 * it should never be wrapped further before display.
 */
public class QueryParseException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String badToken;

    /**
     * Constructs a new parse exception.
     *
     * @param badToken the raw offending token, or {@code null} if not applicable
     * @param message  human-readable explanation, including suggestion text
     */
    public QueryParseException(String badToken, String message) {
        super(message);
        this.badToken = badToken;
    }

    /**
     * @return the offending raw token, or {@code null} if the error is not
     *         tied to a specific token (e.g. structural)
     */
    public String badToken() {
        return badToken;
    }
}

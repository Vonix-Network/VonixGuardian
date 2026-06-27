package network.vonix.guardian.core.command;

/**
 * Loader-agnostic description of an argument kind a subcommand expects.
 *
 * <p>The brigadier bridge in each MC-version {@code common} module maps these
 * to the appropriate {@code ArgumentType} (e.g. {@code StringArgumentType.greedyString()}
 * for {@link #FILTER_TOKENS}). This enum stays free of any MC API so it can
 * live in the loader-agnostic {@code core} module.</p>
 */
public enum ArgumentSpec {

    /**
     * Greedy string of filter tokens for {@code /vg lookup} —
     * e.g. {@code u:Notch t:1h r:10 a:block #count}.
     * Parsed by {@code network.vonix.guardian.core.query.QueryParser}.
     */
    FILTER_TOKENS,

    /**
     * Positive integer page number for paginated lookup output.
     * Optional; defaults to 1.
     */
    PAGE_NUMBER,

    /**
     * Short duration string used by {@code /vg purge} —
     * e.g. {@code 7d}, {@code 24h}, {@code 30m}. Parsed by the same
     * duration grammar as the {@code t:} filter token.
     */
    TIME_SHORT
}

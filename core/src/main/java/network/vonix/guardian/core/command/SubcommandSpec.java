package network.vonix.guardian.core.command;

import java.util.List;
import java.util.Objects;

/**
 * Loader-agnostic description of a single {@code /vg} subcommand.
 *
 * <p>Immutable value type. Each MC-version {@code common} module reads this and
 * wires up brigadier nodes; the {@code core} module never depends on brigadier
 * directly.</p>
 *
 * @param name              subcommand literal (e.g. {@code "rollback"})
 * @param alias             optional alias literal, or {@code null} if none
 * @param permissionNode    canonical permission node — always
 *                          {@code vonixguardian.command.<name>}
 * @param requiresArguments {@code true} if the subcommand cannot execute without
 *                          arguments (loader rejects the bare form)
 * @param helpSummary       one-line description shown by {@code /vg help}
 * @param arguments         ordered list of argument kinds the subcommand accepts;
 *                          empty if the subcommand takes none
 */
public record SubcommandSpec(
    String name,
    String alias,
    String permissionNode,
    boolean requiresArguments,
    String helpSummary,
    List<ArgumentSpec> arguments
) {
    /**
     * Canonical constructor — validates required fields and defensively copies
     * the argument list.
     */
    public SubcommandSpec {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(permissionNode, "permissionNode");
        Objects.requireNonNull(helpSummary, "helpSummary");
        Objects.requireNonNull(arguments, "arguments");
        if (name.isBlank() || permissionNode.isBlank() || helpSummary.isBlank()) {
            throw new IllegalArgumentException("name, permissionNode, helpSummary must be non-blank");
        }
        arguments = List.copyOf(arguments);
    }
}

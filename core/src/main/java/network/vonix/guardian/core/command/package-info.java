/**
 * Loader-agnostic command tree for {@code /vg}.
 *
 * <p>{@link network.vonix.guardian.core.command.CommandSpec} exposes the
 * subcommand names and a data-only {@link
 * network.vonix.guardian.core.command.CommandSpec#all()} tree. MC-version
 * {@code common} modules read this tree and wire up brigadier — the
 * {@code core} module never depends on brigadier directly.</p>
 *
 * <p>Argument kinds are modelled by
 * {@link network.vonix.guardian.core.command.ArgumentSpec}.</p>
 */
package network.vonix.guardian.core.command;

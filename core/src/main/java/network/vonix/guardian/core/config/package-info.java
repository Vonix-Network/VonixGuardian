/**
 * Configuration model and loader for VonixGuardian.
 *
 * <p>This package owns the canonical {@link network.vonix.guardian.core.config.GuardianConfig}
 * record (matching SHARED-CONTRACTS &sect; 9) and a Gson-backed
 * {@link network.vonix.guardian.core.config.ConfigLoader} that reads / writes
 * {@code config/vonixguardian/config.json}.
 *
 * <p>The loader is lenient: lines starting with {@code //}, and trailing
 * {@code # } end-of-line comments, are stripped before parsing. Trailing commas
 * are tolerated via Gson's lenient JSON reader.
 *
 * <p>Writes are atomic — the loader serialises to a {@code .tmp} sibling file
 * and then renames into place.
 *
 * @since 0.1.0
 */
package network.vonix.guardian.core.config;

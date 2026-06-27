/**
 * Loader-agnostic permission resolution.
 *
 * <p>Provides {@link network.vonix.guardian.core.perms.PermissionResolver}, which first attempts a
 * reflective LuckPerms lookup via {@link network.vonix.guardian.core.perms.LuckPermsBridge} and
 * falls back to a loader-supplied op-level function ({@link
 * network.vonix.guardian.core.perms.OpLevelFallback}) when LuckPerms is absent or undecided.
 *
 * <p>This package MUST NOT import {@code net.luckperms.*} — LuckPerms is a soft dependency accessed
 * exclusively through reflection so the core jar stays compilable and runnable without it.
 */
package network.vonix.guardian.core.perms;

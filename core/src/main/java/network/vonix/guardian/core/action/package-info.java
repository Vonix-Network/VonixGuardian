/**
 * Core action model for VonixGuardian.
 *
 * <p>This package defines the canonical, loader-agnostic representation of a single
 * audited event in the Guardian system. The three primary types are:
 * <ul>
 *   <li>{@link network.vonix.guardian.core.action.ActionType} — enum of every kind
 *       of event Guardian can log. The integer ids are stable database keys and
 *       must never be reordered or renumbered.</li>
 *   <li>{@link network.vonix.guardian.core.action.Action} — immutable record of one
 *       logged event.</li>
 *   <li>{@link network.vonix.guardian.core.action.ActionBuilder} — mutable fluent
 *       builder that produces an immutable {@code Action}.</li>
 * </ul>
 *
 * <p>See {@code SHARED-CONTRACTS.md} § 2 for the frozen contract this package
 * implements.
 */
package network.vonix.guardian.core.action;

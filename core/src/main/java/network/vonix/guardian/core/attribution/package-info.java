/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
/**
 * Universal attribution: deciding "who is responsible" for an entity-driven action,
 * across vanilla and modded entities, without any per-mod code.
 *
 * <p>The chain is documented on {@link
 * network.vonix.guardian.core.attribution.AttributionResolver}; this package only
 * contains the contract types ({@link
 * network.vonix.guardian.core.attribution.Attribution}, {@link
 * network.vonix.guardian.core.attribution.AttributionKind}) and the supporting
 * {@link network.vonix.guardian.core.attribution.DamageHistory} ring used by step
 * 5 of the chain.
 *
 * <p>The actual reflection / interface probing lives in the loader modules
 * because it touches MC-version-specific entity classes; {@code core} stays
 * loader-agnostic.
 */
package network.vonix.guardian.core.attribution;

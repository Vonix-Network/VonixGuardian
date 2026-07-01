/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
/**
 * Public, version-stable Java API surface for VonixGuardian (W3-B12+B13).
 *
 * <p>Third-party mods consume {@link network.vonix.guardian.core.api.VonixGuardianAPI}
 * via a reflection soft-dep (mirroring how VG itself talks to LuckPerms — see
 * {@code perms/LuckPermsBridge.java}). See {@code docs/API.md} § "Public Java
 * API (v1)" for wiring examples.
 *
 * <p>Backward-compatibility contract: <b>new methods may be added</b>, but
 * signatures of existing methods and shapes of result records will NOT change
 * within a single {@link network.vonix.guardian.core.api.VonixGuardianAPI#apiVersion()}
 * major.
 */
package network.vonix.guardian.core.api;

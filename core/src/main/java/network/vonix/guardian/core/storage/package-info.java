/**
 * Storage layer for VonixGuardian.
 *
 * <p>Defines {@link network.vonix.guardian.core.storage.GuardianDao} — the contract every
 * backend implementation honours — together with the dialect-aware {@link
 * network.vonix.guardian.core.storage.Schema} DDL, the {@link
 * network.vonix.guardian.core.storage.QueryCompiler} that turns a
 * {@link network.vonix.guardian.core.query.QueryFilter} into safe parameterised SQL, and a
 * {@link network.vonix.guardian.core.storage.StorageFactory} that wires the correct
 * implementation from {@link network.vonix.guardian.core.config.GuardianConfig}.
 *
 * <p>JDBC implementations live in {@link network.vonix.guardian.core.storage.jdbc}.
 */
package network.vonix.guardian.core.storage;

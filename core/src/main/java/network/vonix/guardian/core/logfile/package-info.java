/**
 * JSON-Lines audit log: one file per UTC day, daily rotation with optional gzip,
 * lazy retention pruning. Append-only and independent of the database — used for
 * forensics when the DB is corrupted. See SHARED-CONTRACTS.md § 6.
 */
package network.vonix.guardian.core.logfile;

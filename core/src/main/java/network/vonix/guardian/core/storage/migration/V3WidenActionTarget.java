package network.vonix.guardian.core.storage.migration;

import network.vonix.guardian.core.storage.Schema;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Schema migration <b>v2 → v3</b>: widen {@code vg_actions.target} from
 * {@code VARCHAR(192)} to {@code VARCHAR(4096)}.
 *
 * <h2>Why</h2>
 * {@code vg_actions.target} is a general-purpose free-text column used for block
 * ids ({@code minecraft:stone}), entity keys ({@code minecraft:enderman}), chat
 * message bodies, {@code /tellraw} command payloads, and — most importantly —
 * explosion {@code affectedJoined} strings that concatenate every block affected
 * by a blast.
 *
 * <p>The Berk maintenance-window incident on 2026-07-01 08:05:54 UTC surfaced the
 * bug: {@code Guardian.java#submitChat/Command/Sign/Explosion} shove unbounded
 * free-text into this column (chat up to Minecraft's 256-char cap, {@code /tellraw}
 * commonly 500+ chars, explosion joins documented at 4 KiB). At v2 the column was
 * {@code VARCHAR(192) NOT NULL} — a stray 500-char {@code /tellraw} triggered
 * MySQL error 1406 "Data truncation" and caused the writer to degrade its batch
 * size to 1 in an attempt to isolate the poison row, which then hit again on the
 * next {@code /tellraw} and never recovered.
 *
 * <h2>Dialect handling</h2>
 * <ul>
 *   <li><b>MySQL / MariaDB:</b> {@code ALTER TABLE vg_actions MODIFY target VARCHAR(4096) NOT NULL}.
 *       This is an online DDL on MySQL 5.7+/8.x for widening a VARCHAR within the
 *       same character-set encoding class, and effectively no-copy on MariaDB with
 *       the same encoding. If run inside a transaction, MySQL issues an implicit
 *       commit before the DDL; that's fine here because our runner treats the
 *       ALTER + version stamp as best-effort atomic (see {@link MigrationRunner}).</li>
 *   <li><b>PostgreSQL:</b> {@code ALTER TABLE vg_actions ALTER COLUMN target TYPE VARCHAR(4096)}.
 *       Postgres is fully transactional for DDL and this is a metadata-only change
 *       (widening a varchar never rewrites the table).</li>
 *   <li><b>SQLite:</b> <b>NO-OP.</b> SQLite's type system uses affinity rather
 *       than declared length; {@code VARCHAR(N)} has TEXT affinity and does not
 *       enforce {@code N} at insert time. A 4 KiB target column was silently
 *       accepted by the v2 schema on SQLite already. We still stamp the version
 *       so operators upgrading a SQLite-backed dev instance land at v3 rather
 *       than repeatedly attempting the no-op migration on every boot.</li>
 * </ul>
 *
 * <h2>Idempotency</h2>
 * The {@link MigrationRunner} only invokes {@link #apply(Connection, Schema.Dialect)}
 * when the recorded schema version is exactly 2, so this class does not need to
 * introspect {@code information_schema} to detect a partially-applied v3. If the
 * ALTER happens to fail mid-flight, the transaction is rolled back (Postgres) or
 * the DDL is autocommitted before the version stamp (MySQL); in the MySQL case
 * a subsequent restart re-runs the ALTER against an already-widened column,
 * which MySQL treats as a metadata-only no-op — safe.
 */
public final class V3WidenActionTarget implements Migration {

    @Override
    public int fromVersion() {
        return 2;
    }

    @Override
    public int toVersion() {
        return 3;
    }

    @Override
    public void apply(Connection c, Schema.Dialect dialect) throws SQLException {
        String ddl = switch (dialect) {
            case MYSQL    -> "ALTER TABLE vg_actions MODIFY target VARCHAR(4096) NOT NULL";
            case POSTGRES -> "ALTER TABLE vg_actions ALTER COLUMN target TYPE VARCHAR(4096)";
            case SQLITE   -> null; // TEXT affinity; declared length is decorative.
        };
        if (ddl == null) {
            return;
        }
        try (Statement st = c.createStatement()) {
            st.execute(ddl);
        }
    }

    @Override
    public String label() {
        return "V3WidenActionTarget (v2→v3, widen vg_actions.target to VARCHAR(4096))";
    }
}

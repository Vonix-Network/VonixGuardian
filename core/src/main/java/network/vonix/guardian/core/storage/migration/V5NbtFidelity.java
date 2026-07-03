package network.vonix.guardian.core.storage.migration;

import network.vonix.guardian.core.storage.Schema;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Schema migration <b>v4 → v5</b>: add five nullable Ledger-parity NBT-fidelity
 * columns to {@code vg_actions}:
 * <ul>
 *   <li>{@code old_block_state TEXT} — pre-change block-state property string
 *       (e.g. {@code "facing=north,waterlogged=true,half=lower"}) captured by
 *       the loader-side {@code BlockStateProperties.serialize()} helper.</li>
 *   <li>{@code new_block_state TEXT} — post-change block-state property string
 *       for symmetric {@code /vg restore}.</li>
 *   <li>{@code block_entity_nbt BLOB} — raw NBT bytes for tile entities
 *       (chest contents, spawner data, sign back text, etc.) written via
 *       {@code net.minecraft.nbt.NbtIo.write(CompoundTag, DataOutputStream)}
 *       and read back via {@code NbtIo.read(...)}.</li>
 *   <li>{@code item_nbt BLOB} — raw NBT bytes for item-carrying events
 *       (drops, pickups, container transactions) so
 *       named/enchanted/damaged items round-trip byte-parity with CoreProtect
 *       {@code apply} semantics.</li>
 *   <li>{@code entity_nbt BLOB} — raw NBT bytes for entity kill/spawn events
 *       so {@code /vg restore} can respawn a mob with its original attributes
 *       (custom name, tame owner, potion effects, etc.).</li>
 * </ul>
 *
 * <h2>Why</h2>
 * The v1.3.0 Ledger-comparison audit flagged NBT fidelity as the single
 * largest CoreProtect / Ledger parity gap. Without these columns
 * {@code /vg rollback} downgrades a Netherite-diamond-enchanted sword back to
 * a generic {@code minecraft:netherite_sword}, and cannot restore a chest's
 * pre-break contents at all — the only NBT surface VG previously persisted
 * was the compact-JSON {@code target_meta} column, which is neither big
 * enough for a full BE snapshot nor lossless for enchant / attribute tags.
 *
 * <h2>Dialect handling</h2>
 * All three dialects accept {@code ALTER TABLE ADD COLUMN} for nullable
 * columns without a table rewrite. Byte-array types differ:
 * <ul>
 *   <li><b>MySQL:</b> {@code LONGBLOB} (up to 4 GB). {@code MEDIUMBLOB} caps
 *       at 16 MB which is fine for a single BE today, but LONGBLOB costs
 *       nothing extra on-disk and future-proofs against very large modded
 *       NBTs.</li>
 *   <li><b>PostgreSQL:</b> {@code BYTEA}. Metadata-only ALTER since 11+.</li>
 *   <li><b>SQLite:</b> {@code BLOB}. O(1) sqlite_schema row rewrite.</li>
 * </ul>
 *
 * <h2>Idempotency</h2>
 * Mirrors {@link V4SignMetadata}: each ALTER is issued separately and the
 * per-dialect "column already exists" error is swallowed so a partially
 * applied migration (JVM crash after MySQL's implicit DDL commit but before
 * the version stamp) is safely re-runnable on next boot.
 *
 * @since 1.3.1
 */
public final class V5NbtFidelity implements Migration {

    /** MySQL: {@code ER_DUP_FIELDNAME} — column already exists. */
    private static final int MYSQL_ERR_DUP_FIELDNAME = 1060;
    /** PostgreSQL SQLSTATE for "duplicate column". */
    private static final String PG_SQLSTATE_DUP_COLUMN = "42701";

    @Override
    public int fromVersion() {
        return 4;
    }

    @Override
    public int toVersion() {
        return 5;
    }

    @Override
    public void apply(Connection c, Schema.Dialect dialect) throws SQLException {
        String textType = "TEXT"; // portable across all three dialects
        String blob = switch (dialect) {
            case MYSQL    -> "LONGBLOB";
            case POSTGRES -> "BYTEA";
            case SQLITE   -> "BLOB";
        };
        String[] ddls = new String[] {
            "ALTER TABLE vg_actions ADD COLUMN old_block_state " + textType + " NULL",
            "ALTER TABLE vg_actions ADD COLUMN new_block_state " + textType + " NULL",
            "ALTER TABLE vg_actions ADD COLUMN block_entity_nbt " + blob + " NULL",
            "ALTER TABLE vg_actions ADD COLUMN item_nbt " + blob + " NULL",
            "ALTER TABLE vg_actions ADD COLUMN entity_nbt " + blob + " NULL",
        };
        try (Statement st = c.createStatement()) {
            for (String ddl : ddls) {
                try {
                    st.execute(ddl);
                } catch (SQLException ex) {
                    if (isDuplicateColumn(dialect, ex)) {
                        // Column was added by a previous partial run — safe to skip.
                        continue;
                    }
                    throw ex;
                }
            }
        }
    }

    private static boolean isDuplicateColumn(Schema.Dialect dialect, SQLException ex) {
        return switch (dialect) {
            case MYSQL    -> ex.getErrorCode() == MYSQL_ERR_DUP_FIELDNAME;
            case POSTGRES -> PG_SQLSTATE_DUP_COLUMN.equals(ex.getSQLState());
            case SQLITE   -> {
                String m = ex.getMessage();
                yield m != null && m.toLowerCase().contains("duplicate column");
            }
        };
    }

    @Override
    public String label() {
        return "V5NbtFidelity (v4→v5, add vg_actions.old_block_state/new_block_state/"
             + "block_entity_nbt/item_nbt/entity_nbt)";
    }
}

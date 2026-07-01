package network.vonix.guardian.core.storage.dbmigrate;

import network.vonix.guardian.core.config.GuardianConfig;
import network.vonix.guardian.core.storage.GuardianDao;
import network.vonix.guardian.core.storage.StorageFactory;

import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Loader-agnostic implementation of {@code /vg migrate-db <target-type> CONFIRM}.
 *
 * <p>Every cell wires the same brigadier surface for this subcommand
 * ({@code migrate-db <target-type> [CONFIRM]}); the actual work lives here so the
 * eight per-loader command files stay near-identical to their pre-1.2.0 shape.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>CONSOLE-ONLY. Cell layer refuses invocation from a player and never calls this class.</li>
 *   <li>Requires operator to confirm with a second literal argument {@code CONFIRM}
 *       (matches CoreProtect's Patreon-build safety pattern for {@code /co migrate-db}).</li>
 *   <li>Reads the new backend descriptor from
 *       {@link GuardianConfig.Database#migrationTarget()}. If unset, refuses.</li>
 * </ul>
 *
 * @since 1.2.0
 */
public final class MigrateDbCommand {

    /** Known target-type tokens accepted on the command line. */
    public static final Set<String> TARGET_TYPES = Set.of("sqlite", "mysql", "postgresql");

    /** Confirmation literal — CoreProtect uses the same shape. */
    public static final String CONFIRM_TOKEN = "CONFIRM";

    /** Copy chunk size (rows per SELECT/INSERT batch). */
    public static final int CHUNK_SIZE = 1000;

    private MigrateDbCommand() {}

    /**
     * Run a migration to the operator-declared target backend.
     *
     * @param g          live Guardian facade — its {@link GuardianDao} is the source
     * @param targetType user-supplied target type token
     * @param confirmed  {@code true} if the operator passed {@link #CONFIRM_TOKEN} as
     *                   the second argument
     * @param printLine  sink for every operator-facing line (console feedback +
     *                   progress ticks). Never receives {@code null}.
     * @return true on completion, false if pre-flight checks refused the migration
     */
    public static boolean run(network.vonix.guardian.core.Guardian g,
                              String targetType,
                              boolean confirmed,
                              Consumer<String> printLine) {
        if (targetType == null || !TARGET_TYPES.contains(targetType.toLowerCase(Locale.ROOT))) {
            printLine.accept("[VonixGuardian] migrate-db: unknown target type '" + targetType
                + "'. Expected one of " + TARGET_TYPES + ".");
            return false;
        }
        String target = targetType.toLowerCase(Locale.ROOT);

        GuardianConfig.Database dbCfg = g.config().database();
        GuardianConfig.MigrationTarget mt = dbCfg != null ? dbCfg.migrationTarget() : null;
        if (mt == null) {
            printLine.accept("[VonixGuardian] migrate-db: refusing — no `database.migrationTarget` "
                + "block found in config. Add one describing the destination backend, "
                + "then re-run this command.");
            return false;
        }
        if (!target.equals(mt.type() == null ? "" : mt.type().toLowerCase(Locale.ROOT))) {
            printLine.accept("[VonixGuardian] migrate-db: refusing — command asked to migrate to '"
                + target + "' but config.database.migrationTarget.type is '" + mt.type() + "'. "
                + "Reconcile the two, then re-run.");
            return false;
        }

        if (!confirmed) {
            printLine.accept("[VonixGuardian] migrate-db: about to copy every audit row from '"
                + dbCfg.type() + "' to '" + target + "'.");
            printLine.accept("[VonixGuardian] This is a one-way operation — the target must be "
                + "at schema v" + network.vonix.guardian.core.storage.Schema.CURRENT_VERSION + " and empty.");
            printLine.accept("[VonixGuardian] To proceed, run: /vg migrate-db " + target + " "
                + CONFIRM_TOKEN);
            return false;
        }

        // Materialise the destination Database record from the MigrationTarget block
        // and open a fresh DAO for it. This DAO is short-lived: initialised, migrated
        // to, then closed.
        GuardianConfig.Database destCfg = new GuardianConfig.Database(
            mt.type(), mt.file(), mt.jdbcUrl(), mt.user(), mt.password(), null);
        GuardianConfig destWrapper = new GuardianConfig(
            destCfg,
            g.config().queue(), g.config().logFile(), g.config().actions(),
            g.config().permissions(), g.config().lookup(), g.config().privacy(),
            g.config().purge(), g.config().theme());

        printLine.accept("[VonixGuardian] migrate-db: opening destination backend (" + target + ")...");
        try (GuardianDao destDao = StorageFactory.open(destWrapper)) {
            destDao.init();

            BackendMigrationJob.ProgressCallback cb = update ->
                printLine.accept(String.format(
                    "[VonixGuardian]   %-28s %8d / %8d rows  (%d ms elapsed)",
                    update.table(), update.rowsCopied(), update.rowsTotal(), update.elapsedMs()));

            BackendMigrationJob job = new BackendMigrationJob(g.dao(), destDao, CHUNK_SIZE, cb);
            BackendMigrationJob.Result result = job.run();

            printLine.accept("[VonixGuardian] migrate-db: DONE. Copied "
                + result.totalRows() + " rows in " + result.elapsedMs() + " ms.");
            printLine.accept("[VonixGuardian] Update config.database.type to '" + target
                + "' (and matching connection fields) and restart the server to use the new backend.");
            return true;
        } catch (Exception ex) {
            printLine.accept("[VonixGuardian] migrate-db: FAILED — " + ex.getMessage());
            return false;
        }
    }
}

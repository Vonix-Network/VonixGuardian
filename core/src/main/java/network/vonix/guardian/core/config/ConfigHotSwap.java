package network.vonix.guardian.core.config;

import java.util.ArrayList;
import java.util.List;

/**
 * Applies one {@code /vg config set} key to a copy of the live config.
 *
 * <p>Every write starts from the current {@link GuardianConfig.Actions} and
 * changes a single field. Callers must not rebuild {@code Actions} through a
 * deprecated constructor: the 32-argument shim substitutes empty lists for
 * {@code itemBlacklist} and {@code entityBlacklist}, and saving that copy
 * wipes the blacklists on disk.</p>
 */
public final class ConfigHotSwap {

    private ConfigHotSwap() {}

    /**
     * @return the updated config, or {@code null} when {@code key} is not a
     *         hot-swap key
     * @throws IllegalArgumentException when {@code value} cannot be parsed
     */
    public static GuardianConfig apply(GuardianConfig c, String key, String value) {
        if (c == null || key == null) return null;
        GuardianConfig.Actions a = c.actions();
        GuardianConfig.LogFile lf = c.logFile();
        GuardianConfig.Lookup l = c.lookup();
        GuardianConfig.Privacy pr = c.privacy();
        GuardianConfig.Purge pu = c.purge();
        return switch (key) {
            case "theme" -> copy(c, c.actions(), value, c.language());
            case "logFile.enabled" -> new GuardianConfig(
                c.database(), c.queue(),
                new GuardianConfig.LogFile(parseBool(value, key), lf.directory(), lf.gzipRotated(),
                    lf.retentionDays(), lf.forceSyncOnFlush()),
                a, c.permissions(), l, pr, pu, c.storage(), c.rollback(), c.theme(), c.language());
            case "lookup.defaultPageSize" -> withLookup(c, new GuardianConfig.Lookup(
                parseInt(value, key), l.maxRadius(), l.maxResultRows(), l.maxConcurrent()));
            case "lookup.maxRadius" -> withLookup(c, new GuardianConfig.Lookup(
                l.defaultPageSize(), parseInt(value, key), l.maxResultRows(), l.maxConcurrent()));
            case "lookup.maxResultRows" -> withLookup(c, new GuardianConfig.Lookup(
                l.defaultPageSize(), l.maxRadius(), parseInt(value, key), l.maxConcurrent()));
            case "privacy.hashIps" -> new GuardianConfig(
                c.database(), c.queue(), lf, a, c.permissions(), l,
                new GuardianConfig.Privacy(parseBool(value, key), pr.salt()),
                pu, c.storage(), c.rollback(), c.theme(), c.language());
            case "purge.minAgeSecondsConsole" -> withPurge(c, new GuardianConfig.Purge(
                parseLong(value, key), pu.minAgeSecondsInGame(), pu.autoPurgeSeconds(), pu.autoPurgeTime()));
            case "purge.minAgeSecondsInGame" -> withPurge(c, new GuardianConfig.Purge(
                pu.minAgeSecondsConsole(), parseLong(value, key), pu.autoPurgeSeconds(), pu.autoPurgeTime()));
            case "purge.autoPurgeSeconds" -> withPurge(c, new GuardianConfig.Purge(
                pu.minAgeSecondsConsole(), pu.minAgeSecondsInGame(), parseLong(value, key), pu.autoPurgeTime()));
            case "purge.autoPurgeTime" -> withPurge(c, new GuardianConfig.Purge(
                pu.minAgeSecondsConsole(), pu.minAgeSecondsInGame(), pu.autoPurgeSeconds(), value));
            case "actions.logBlocks" -> withActions(c, edit(a).logBlocks(parseBool(value, key)));
            case "actions.logContainers" -> withActions(c, edit(a).logContainers(parseBool(value, key)));
            case "actions.logItems" -> withActions(c, edit(a).logItems(parseBool(value, key)));
            case "actions.logEntities" -> withActions(c, edit(a).logEntities(parseBool(value, key)));
            case "actions.logExplosions" -> withActions(c, edit(a).logExplosions(parseBool(value, key)));
            case "actions.logChat" -> withActions(c, edit(a).logChat(parseBool(value, key)));
            case "actions.logCommands" -> withActions(c, edit(a).logCommands(parseBool(value, key)));
            case "actions.logSessions" -> withActions(c, edit(a).logSessions(parseBool(value, key)));
            case "actions.logSigns" -> withActions(c, edit(a).logSigns(parseBool(value, key)));
            case "actions.logInteractions" -> withActions(c, edit(a).logInteractions(parseBool(value, key)));
            case "actions.logWorldEvents" -> withActions(c, edit(a).logWorldEvents(parseBool(value, key)));
            case "actions.entityBlockChangeCoalesceWindowMs" ->
                withActions(c, edit(a).coalesceWindow(parseLong(value, key)));
            case "actions.entityBlockChangeMaxTracked" ->
                withActions(c, edit(a).maxTracked(parseInt(value, key)));
            case "actions.entityChangeLogAllEntities" ->
                withActions(c, edit(a).logAllEntities(parseBool(value, key)));
            case "actions.mixinHotEvents" -> withActions(c, edit(a).mixinHotEvents(parseBool(value, key)));
            case "actions.itemBlacklist" -> withActions(c, a.withItemBlacklist(parseList(value)));
            case "actions.entityBlacklist" -> withActions(c, a.withEntityBlacklist(parseList(value)));
            case "storage.persistNbt" -> new GuardianConfig(
                c.database(), c.queue(), lf, a, c.permissions(), l, pr, pu,
                new GuardianConfig.Storage(parseBool(value, key)),
                c.rollback(), c.theme(), c.language());
            case "rollback.explosionSupplementalReach" -> new GuardianConfig(
                c.database(), c.queue(), lf, a, c.permissions(), l, pr, pu, c.storage(),
                new GuardianConfig.Rollback(parseInt(value, key), c.rollback().mutationBatchSize(),
                    c.rollback().requireConfirm()),
                c.theme(), c.language());
            case "language" -> copy(c, a, c.theme(), value);
            default -> null;
        };
    }

    /** @return the displayed value, or {@code null} when {@code key} is unknown */
    public static String read(GuardianConfig c, String key) {
        if (c == null || key == null) return null;
        return switch (key) {
            case "theme" -> c.theme();
            case "logFile.enabled" -> Boolean.toString(c.logFile().enabled());
            case "lookup.defaultPageSize" -> Integer.toString(c.lookup().defaultPageSize());
            case "lookup.maxRadius" -> Integer.toString(c.lookup().maxRadius());
            case "lookup.maxResultRows" -> Integer.toString(c.lookup().maxResultRows());
            case "privacy.hashIps" -> Boolean.toString(c.privacy().hashIps());
            case "purge.minAgeSecondsConsole" -> Long.toString(c.purge().minAgeSecondsConsole());
            case "purge.minAgeSecondsInGame" -> Long.toString(c.purge().minAgeSecondsInGame());
            case "purge.autoPurgeSeconds" -> Long.toString(c.purge().autoPurgeSeconds());
            case "purge.autoPurgeTime" -> c.purge().autoPurgeTime();
            case "actions.logBlocks" -> Boolean.toString(c.actions().logBlocks());
            case "actions.logContainers" -> Boolean.toString(c.actions().logContainers());
            case "actions.logItems" -> Boolean.toString(c.actions().logItems());
            case "actions.logEntities" -> Boolean.toString(c.actions().logEntities());
            case "actions.logExplosions" -> Boolean.toString(c.actions().logExplosions());
            case "actions.logChat" -> Boolean.toString(c.actions().logChat());
            case "actions.logCommands" -> Boolean.toString(c.actions().logCommands());
            case "actions.logSessions" -> Boolean.toString(c.actions().logSessions());
            case "actions.logSigns" -> Boolean.toString(c.actions().logSigns());
            case "actions.logInteractions" -> Boolean.toString(c.actions().logInteractions());
            case "actions.logWorldEvents" -> Boolean.toString(c.actions().logWorldEvents());
            case "actions.entityBlockChangeCoalesceWindowMs" ->
                Long.toString(c.actions().entityBlockChangeCoalesceWindowMs());
            case "actions.entityBlockChangeMaxTracked" ->
                Integer.toString(c.actions().entityBlockChangeMaxTracked());
            case "actions.entityChangeLogAllEntities" ->
                Boolean.toString(c.actions().entityChangeLogAllEntities());
            case "actions.mixinHotEvents" -> Boolean.toString(c.actions().mixinHotEvents());
            case "actions.itemBlacklist" -> String.join(", ", c.actions().itemBlacklist());
            case "actions.entityBlacklist" -> String.join(", ", c.actions().entityBlacklist());
            case "storage.persistNbt" -> Boolean.toString(c.storage().persistNbt());
            case "rollback.explosionSupplementalReach" ->
                Integer.toString(c.rollback().explosionSupplementalReach());
            case "language" -> c.language();
            default -> null;
        };
    }

    private static GuardianConfig copy(GuardianConfig c, GuardianConfig.Actions actions,
                                       String theme, String language) {
        return new GuardianConfig(
            c.database(), c.queue(), c.logFile(), actions, c.permissions(), c.lookup(),
            c.privacy(), c.purge(), c.storage(), c.rollback(), theme, language);
    }

    private static GuardianConfig withActions(GuardianConfig c, GuardianConfig.Actions actions) {
        return copy(c, actions, c.theme(), c.language());
    }

    private static GuardianConfig withLookup(GuardianConfig c, GuardianConfig.Lookup lookup) {
        return new GuardianConfig(
            c.database(), c.queue(), c.logFile(), c.actions(), c.permissions(), lookup,
            c.privacy(), c.purge(), c.storage(), c.rollback(), c.theme(), c.language());
    }

    private static GuardianConfig withPurge(GuardianConfig c, GuardianConfig.Purge purge) {
        return new GuardianConfig(
            c.database(), c.queue(), c.logFile(), c.actions(), c.permissions(), c.lookup(),
            c.privacy(), purge, c.storage(), c.rollback(), c.theme(), c.language());
    }

    private static Editor edit(GuardianConfig.Actions actions) {
        return new Editor(actions);
    }

    /**
     * Canonical-constructor copy. Every list the live config already has is
     * passed through unless this edit replaces it.
     */
    private static final class Editor {
        private final GuardianConfig.Actions a;
        private boolean logBlocks, logContainers, logItems, logEntities, logExplosions;
        private boolean logChat, logCommands, logSessions, logSigns, logInteractions, logWorldEvents;
        private long coalesce;
        private int maxTracked;
        private boolean logAll;
        private boolean mixin;

        Editor(GuardianConfig.Actions a) {
            this.a = a;
            this.logBlocks = a.logBlocks();
            this.logContainers = a.logContainers();
            this.logItems = a.logItems();
            this.logEntities = a.logEntities();
            this.logExplosions = a.logExplosions();
            this.logChat = a.logChat();
            this.logCommands = a.logCommands();
            this.logSessions = a.logSessions();
            this.logSigns = a.logSigns();
            this.logInteractions = a.logInteractions();
            this.logWorldEvents = a.logWorldEvents();
            this.coalesce = a.entityBlockChangeCoalesceWindowMs();
            this.maxTracked = a.entityBlockChangeMaxTracked();
            this.logAll = a.entityChangeLogAllEntities();
            this.mixin = a.mixinHotEvents();
        }

        GuardianConfig.Actions logBlocks(boolean v) { logBlocks = v; return build(); }
        GuardianConfig.Actions logContainers(boolean v) { logContainers = v; return build(); }
        GuardianConfig.Actions logItems(boolean v) { logItems = v; return build(); }
        GuardianConfig.Actions logEntities(boolean v) { logEntities = v; return build(); }
        GuardianConfig.Actions logExplosions(boolean v) { logExplosions = v; return build(); }
        GuardianConfig.Actions logChat(boolean v) { logChat = v; return build(); }
        GuardianConfig.Actions logCommands(boolean v) { logCommands = v; return build(); }
        GuardianConfig.Actions logSessions(boolean v) { logSessions = v; return build(); }
        GuardianConfig.Actions logSigns(boolean v) { logSigns = v; return build(); }
        GuardianConfig.Actions logInteractions(boolean v) { logInteractions = v; return build(); }
        GuardianConfig.Actions logWorldEvents(boolean v) { logWorldEvents = v; return build(); }
        GuardianConfig.Actions coalesceWindow(long v) { coalesce = v; return build(); }
        GuardianConfig.Actions maxTracked(int v) { maxTracked = v; return build(); }
        GuardianConfig.Actions logAllEntities(boolean v) { logAll = v; return build(); }
        GuardianConfig.Actions mixinHotEvents(boolean v) { mixin = v; return build(); }

        private GuardianConfig.Actions build() {
            return new GuardianConfig.Actions(
                logBlocks, logContainers, logItems, logEntities, logExplosions, logChat,
                logCommands, logSessions, logSigns, logInteractions, logWorldEvents,
                a.worldBlacklist(), a.blockBlacklist(), a.sourceBlacklist(),
                a.itemBlacklist(), a.entityBlacklist(),
                coalesce, maxTracked,
                a.entityChangeAllowlist(), logAll,
                a.logNaturalBreaks(), a.logTreeGrowth(), a.logMushroomGrowth(), a.logVineGrowth(),
                a.logSculkSpread(), a.logPortals(), a.logWaterFlow(), a.logLavaFlow(),
                a.logFireExtinguish(), a.logCampfireStart(), a.logHopperMetaFilter(),
                a.logDuplicateSuppression(), a.logCancelledChat(),
                mixin
            );
        }
    }

    static List<String> parseList(String value) {
        if (value == null || value.isBlank() || "none".equalsIgnoreCase(value.trim()) || "[]".equals(value.trim())) {
            return List.of();
        }
        String[] parts = value.split("[,\\s]+");
        List<String> list = new ArrayList<>();
        for (String p : parts) {
            if (!p.isBlank()) list.add(p.trim());
        }
        return List.copyOf(list);
    }

    private static boolean parseBool(String value, String key) {
        if ("true".equalsIgnoreCase(value)) return true;
        if ("false".equalsIgnoreCase(value)) return false;
        throw new IllegalArgumentException(key + " must be true or false");
    }

    private static int parseInt(String value, String key) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be an integer");
        }
    }

    private static long parseLong(String value, String key) {
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be an integer");
        }
    }
}

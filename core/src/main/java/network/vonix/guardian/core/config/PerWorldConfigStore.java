package network.vonix.guardian.core.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Loads and caches per-world {@link GuardianConfig.Actions} overrides mirroring
 * CoreProtect's {@code world_nether.yml} shadow pattern.
 *
 * <p>File layout: {@code config/vonixguardian/worlds/<world_key>.json}. The
 * on-disk world key encodes the vanilla registry id with the colon replaced by
 * a double underscore (e.g. {@code minecraft:the_nether} → filename
 * {@code minecraft__the_nether.json}). Each file is a partial {@code Actions}
 * block — any subset of the 18 fields; missing fields inherit from the root
 * {@code actions.*} block.
 *
 * <p>Overrides are precomputed at load-time into a
 * {@code Map<String worldId, Actions>} — the hot path does a single map lookup
 * and never allocates. Missing worlds return {@code null} and callers fall back
 * to the root {@code Actions} block.
 *
 * <p>Thread-safe: the cache map is replaced wholesale under
 * {@link #reload(Path)} via a {@code volatile} reference; readers see a
 * consistent snapshot.
 *
 * @since 1.1.7 (W3-B5)
 */
public final class PerWorldConfigStore {

    private static final Logger LOG = LoggerFactory.getLogger(PerWorldConfigStore.class);

    private static final Gson LENIENT = new GsonBuilder().setLenient().create();

    private volatile GuardianConfig.Actions rootActions;
    private volatile Map<String, GuardianConfig.Actions> cache = Map.of();
    private volatile List<String> loadedFilenames = List.of();

    /**
     * @param rootActions the root {@code actions.*} block used as the fallback
     *                    for fields absent from a per-world override; must not
     *                    be {@code null}
     */
    public PerWorldConfigStore(GuardianConfig.Actions rootActions) {
        this.rootActions = Objects.requireNonNull(rootActions, "rootActions");
    }

    /**
     * Reload the store from {@code worldsDir}. If the directory does not exist
     * the cache is cleared. Files that fail to parse are logged and skipped;
     * one bad file does not poison the whole store.
     *
     * @param worldsDir directory containing per-world JSON files; may be
     *                  {@code null} or non-existent (both clear the cache)
     */
    public void reload(Path worldsDir) {
        Map<String, GuardianConfig.Actions> next = new HashMap<>();
        List<String> filenames = new ArrayList<>();
        if (worldsDir == null || !Files.isDirectory(worldsDir)) {
            this.cache = Map.copyOf(next);
            this.loadedFilenames = List.copyOf(filenames);
            return;
        }
        try (Stream<Path> s = Files.list(worldsDir)) {
            s.filter(p -> p.getFileName().toString().endsWith(".json"))
             .sorted()
             .forEach(p -> {
                 String fname = p.getFileName().toString();
                 String key = fname.substring(0, fname.length() - ".json".length());
                 String worldId = key.replace("__", ":");
                 try {
                     String raw = Files.readString(p, StandardCharsets.UTF_8);
                     JsonElement je = LENIENT.fromJson(raw, JsonElement.class);
                     if (je == null || !je.isJsonObject()) {
                         LOG.warn("Per-world override {} is not a JSON object; skipped", p);
                         return;
                     }
                     GuardianConfig.Actions merged = merge(rootActions, je.getAsJsonObject());
                     next.put(worldId, merged);
                     filenames.add(fname);
                 } catch (IOException | JsonSyntaxException e) {
                     LOG.warn("Failed to load per-world override {}: {}", p, e.getMessage());
                 }
             });
        } catch (IOException e) {
            LOG.warn("Failed to scan per-world overrides dir {}: {}", worldsDir, e.getMessage());
        }
        this.cache = Map.copyOf(next);
        this.loadedFilenames = List.copyOf(filenames);
    }

    /**
     * Update the root fallback {@code Actions} block. Call this after
     * {@code /vg reload} swaps in a new root config, followed by {@link #reload(Path)}
     * to re-materialize the merged per-world views against the new root.
     */
    public void updateRoot(GuardianConfig.Actions newRoot) {
        this.rootActions = Objects.requireNonNull(newRoot, "newRoot");
    }

    /**
     * @param worldId vanilla registry id (e.g. {@code minecraft:the_nether})
     * @return the fully-merged {@link GuardianConfig.Actions} block for
     *         {@code worldId}, or {@code null} if that world has no override
     *         file (caller falls back to root)
     */
    public GuardianConfig.Actions overrideFor(String worldId) {
        return cache.get(worldId);
    }

    /** @return the set of world ids currently overridden (immutable snapshot). */
    public Set<String> overriddenWorlds() {
        return cache.keySet();
    }

    /** @return the list of loaded override filenames, sorted (immutable snapshot). */
    public List<String> loadedFilenames() {
        return loadedFilenames;
    }

    // ------------------------------------------------------------------ merge

    private static GuardianConfig.Actions merge(GuardianConfig.Actions root, JsonObject o) {
        return new GuardianConfig.Actions(
            bool(o, "logBlocks", root.logBlocks()),
            bool(o, "logContainers", root.logContainers()),
            bool(o, "logItems", root.logItems()),
            bool(o, "logEntities", root.logEntities()),
            bool(o, "logExplosions", root.logExplosions()),
            bool(o, "logChat", root.logChat()),
            bool(o, "logCommands", root.logCommands()),
            bool(o, "logSessions", root.logSessions()),
            bool(o, "logSigns", root.logSigns()),
            bool(o, "logInteractions", root.logInteractions()),
            bool(o, "logWorldEvents", root.logWorldEvents()),
            stringList(o, "worldBlacklist", root.worldBlacklist()),
            stringList(o, "blockBlacklist", root.blockBlacklist()),
            stringList(o, "sourceBlacklist", root.sourceBlacklist()),
            longVal(o, "entityBlockChangeCoalesceWindowMs", root.entityBlockChangeCoalesceWindowMs()),
            intVal(o, "entityBlockChangeMaxTracked", root.entityBlockChangeMaxTracked()),
            stringList(o, "entityChangeAllowlist", root.entityChangeAllowlist()),
            bool(o, "entityChangeLogAllEntities", root.entityChangeLogAllEntities()),
            bool(o, "logNaturalBreaks", root.logNaturalBreaks()),
            bool(o, "logTreeGrowth", root.logTreeGrowth()),
            bool(o, "logMushroomGrowth", root.logMushroomGrowth()),
            bool(o, "logVineGrowth", root.logVineGrowth()),
            bool(o, "logSculkSpread", root.logSculkSpread()),
            bool(o, "logPortals", root.logPortals()),
            bool(o, "logWaterFlow", root.logWaterFlow()),
            bool(o, "logLavaFlow", root.logLavaFlow()),
            bool(o, "logFireExtinguish", root.logFireExtinguish()),
            bool(o, "logCampfireStart", root.logCampfireStart()),
            bool(o, "logHopperMetaFilter", root.logHopperMetaFilter()),
            bool(o, "logDuplicateSuppression", root.logDuplicateSuppression()),
            bool(o, "logCancelledChat", root.logCancelledChat()),
            bool(o, "mixinHotEvents", root.mixinHotEvents())
        );
    }

    private static boolean bool(JsonObject o, String k, boolean fallback) {
        JsonElement e = o.get(k);
        return (e == null || e.isJsonNull()) ? fallback : e.getAsBoolean();
    }

    private static long longVal(JsonObject o, String k, long fallback) {
        JsonElement e = o.get(k);
        return (e == null || e.isJsonNull()) ? fallback : e.getAsLong();
    }

    private static int intVal(JsonObject o, String k, int fallback) {
        JsonElement e = o.get(k);
        return (e == null || e.isJsonNull()) ? fallback : e.getAsInt();
    }

    private static List<String> stringList(JsonObject o, String k, List<String> fallback) {
        JsonElement e = o.get(k);
        if (e == null || e.isJsonNull()) {
            return fallback == null ? Collections.emptyList() : fallback;
        }
        if (!e.isJsonArray()) {
            throw new JsonSyntaxException("field " + k + " must be a JSON array");
        }
        JsonArray arr = e.getAsJsonArray();
        List<String> out = new ArrayList<>(arr.size());
        for (JsonElement it : arr) {
            out.add(it.isJsonNull() ? null : it.getAsString());
        }
        return out;
    }
}

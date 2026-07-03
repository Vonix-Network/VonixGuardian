package network.vonix.guardian.core.rollback;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Parser/serializer for the affected-block list stored in
 * {@code Action.targetId} for {@link network.vonix.guardian.core.action.ActionType#EXPLOSION}
 * rows.
 *
 * <p>Storage format (matches all 8 loader cells' {@code *Events.java}):</p>
 * <pre>
 *     x1:y1:z1=blockId[|meta],x2:y2:z2=blockId[|meta],...
 * </pre>
 *
 * <p>Notes:</p>
 * <ul>
 *   <li>The list is silently truncated at ~4 KiB by the event producers; that
 *       is not this class's concern.</li>
 *   <li>Malformed entries (bad coord, missing {@code =}, non-numeric axis) are
 *       skipped with a debug log rather than failing the whole list — a
 *       corrupt tail must not sabotage the head.</li>
 *   <li>{@code minecraft:air} is preserved verbatim; the rollback engine uses
 *       it as the "clear" sentinel when re-applying an explosion via restore.</li>
 * </ul>
 *
 * <p>W5 (v1.3.0): introduced so {@link RollbackEngine} can expand the
 * affected-list at plan time — matching CoreProtect's "loop-through-affected-list
 * at rollback time" semantics — instead of only checking the blast center
 * against the caller's radius.</p>
 */
public final class ExplosionAffectedList {

    private static final Logger LOG = LoggerFactory.getLogger(ExplosionAffectedList.class);

    /** A single {@code x:y:z=blockId[|meta]} entry. */
    public record Entry(int x, int y, int z, String blockId, String meta) {
        public Entry {
            Objects.requireNonNull(blockId, "blockId");
        }
    }

    private final List<Entry> entries;

    private ExplosionAffectedList(List<Entry> entries) {
        this.entries = Collections.unmodifiableList(entries);
    }

    /**
     * Parse the affected-list serialized form. {@code null} or blank returns
     * an empty list.
     */
    public static ExplosionAffectedList parse(String serialized) {
        if (serialized == null || serialized.isBlank()) {
            return new ExplosionAffectedList(List.of());
        }
        List<Entry> out = new ArrayList<>();
        for (String rawEntry : serialized.split(",")) {
            Entry e = parseEntry(rawEntry);
            if (e != null) {
                out.add(e);
            }
        }
        return new ExplosionAffectedList(out);
    }

    /**
     * Serialize this list back to the on-disk format. Empty list returns the
     * empty string.
     */
    public String serialize() {
        if (entries.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Entry e : entries) {
            if (!first) sb.append(',');
            first = false;
            sb.append(e.x).append(':').append(e.y).append(':').append(e.z)
              .append('=').append(e.blockId);
            if (e.meta != null && !e.meta.isEmpty()) {
                sb.append('|').append(e.meta);
            }
        }
        return sb.toString();
    }

    /** @return unmodifiable view of the parsed entries. */
    public List<Entry> entries() {
        return entries;
    }

    /** @return {@code true} if there are no entries (empty or all malformed). */
    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /** @return number of parsed entries. */
    public int size() {
        return entries.size();
    }

    /**
     * Whether ANY affected-block coordinate falls within a cubic bounding box
     * of half-side {@code radius} centered on {@code (cx, cy, cz)}.
     *
     * <p>This is the CoreProtect-parity check used by
     * {@link RollbackEngine#plan(network.vonix.guardian.core.query.QueryFilter,
     * RollbackResult.Mode, java.util.UUID, RollbackOptions)} to admit
     * EXPLOSION rows whose center coord is outside the filter radius but whose
     * damage reaches into it.</p>
     *
     * <p>Semantics match {@link network.vonix.guardian.core.storage.QueryCompiler}:
     * inclusive bounding box on X/Z (and Y when Y filter applies). This method
     * checks X, Y, Z uniformly — callers with only an X/Z-plane filter should
     * skip the Y check by passing {@link Integer#MAX_VALUE} for {@code radius}
     * or pre-checking themselves.</p>
     *
     * @param cx     center X (block coord)
     * @param cy     center Y; may be {@code null} to skip the Y-axis check
     * @param cz     center Z (block coord)
     * @param radius half-side of the cubic bounding box, in blocks; must be &ge; 0
     * @return {@code true} if any entry lies within the box
     */
    public boolean anyWithinRadius(int cx, Integer cy, int cz, int radius) {
        if (radius < 0) return true; // #global — accept every explosion
        int minX = cx - radius, maxX = cx + radius;
        int minZ = cz - radius, maxZ = cz + radius;
        Integer minY = cy == null ? null : cy - radius;
        Integer maxY = cy == null ? null : cy + radius;
        for (Entry e : entries) {
            if (e.x < minX || e.x > maxX) continue;
            if (e.z < minZ || e.z > maxZ) continue;
            if (minY != null && (e.y < minY || e.y > maxY)) continue;
            return true;
        }
        return false;
    }

    // -- internals ---------------------------------------------------------

    private static Entry parseEntry(String raw) {
        if (raw == null) return null;
        String entry = raw.trim();
        if (entry.isEmpty()) return null;
        try {
            int eq = entry.indexOf('=');
            if (eq <= 0 || eq == entry.length() - 1) {
                LOG.debug("ExplosionAffectedList: malformed entry (missing '='): '{}'", entry);
                return null;
            }
            String pos = entry.substring(0, eq);
            String rest = entry.substring(eq + 1);
            String blockId;
            String meta = null;
            int pipe = rest.indexOf('|');
            if (pipe >= 0) {
                blockId = rest.substring(0, pipe).trim();
                meta = rest.substring(pipe + 1);
            } else {
                blockId = rest.trim();
            }
            if (blockId.isEmpty()) {
                LOG.debug("ExplosionAffectedList: malformed entry (empty block id): '{}'", entry);
                return null;
            }
            String[] xyz = pos.split(":");
            if (xyz.length != 3) {
                LOG.debug("ExplosionAffectedList: malformed entry (need x:y:z): '{}'", entry);
                return null;
            }
            int x = Integer.parseInt(xyz[0].trim());
            int y = Integer.parseInt(xyz[1].trim());
            int z = Integer.parseInt(xyz[2].trim());
            return new Entry(x, y, z, blockId, meta);
        } catch (NumberFormatException e) {
            LOG.debug("ExplosionAffectedList: malformed entry (non-numeric coord): '{}'", entry);
            return null;
        }
    }
}

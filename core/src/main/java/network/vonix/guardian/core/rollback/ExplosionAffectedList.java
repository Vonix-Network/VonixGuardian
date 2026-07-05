package network.vonix.guardian.core.rollback;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Parser/serializer for the affected-block list stored in
 * {@code Action.targetId} for {@link network.vonix.guardian.core.action.ActionType#EXPLOSION}
 * rows.
 *
 * <p>Storage format (matches all 8 loader cells' {@code *Events.java}):</p>
 * <pre>
 *     x1:y1:z1=blockId[|meta[|base64BlockEntityNbt]],...
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
    private static final int SIDECAR_MAGIC = 0x56474531; // "VGE1"
    private static final int SIDECAR_VERSION = 1;
    private static final int MAX_SIDECAR_ENTRIES = 100_000;
    private static final int MAX_SIDECAR_FIELD_BYTES = 16 * 1024 * 1024;

    private record CoordKey(int x, int y, int z) {}

    /** A single {@code x:y:z=blockId[|meta[|base64BlockEntityNbt]]} entry. */
    public record Entry(int x, int y, int z, String blockId, String meta, byte[] blockEntityNbt) {
        public Entry {
            Objects.requireNonNull(blockId, "blockId");
        }

        public Entry(int x, int y, int z, String blockId, String meta) {
            this(x, y, z, blockId, meta, null);
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
     * Parse the compact target column and merge the optional binary sidecar
     * carried by EXPLOSION rows' {@code block_entity_nbt} column. The sidecar
     * keeps block-state properties and block-entity NBT out of the VARCHAR(4096)
     * target column while preserving the legacy coord/id target shape.
     */
    public static ExplosionAffectedList parse(String serialized, byte[] sidecar) {
        ExplosionAffectedList base = parse(serialized);
        if (base.isEmpty() || sidecar == null || sidecar.length == 0) return base;
        Map<CoordKey, Entry> extras = readSidecar(sidecar);
        if (extras.isEmpty()) return base;
        List<Entry> out = new ArrayList<>(base.entries.size());
        for (Entry e : base.entries) {
            Entry extra = extras.get(new CoordKey(e.x, e.y, e.z));
            if (extra == null) {
                out.add(e);
            } else {
                out.add(new Entry(e.x, e.y, e.z, e.blockId,
                    extra.meta != null ? extra.meta : e.meta,
                    extra.blockEntityNbt != null ? extra.blockEntityNbt : e.blockEntityNbt));
            }
        }
        return new ExplosionAffectedList(out);
    }

    /**
     * Serialize per-affected-block metadata into a binary sidecar. Only entries
     * carrying state props or BE NBT are written; pure coord/id entries return
     * {@code null} so callers can keep the DB column empty.
     */
    public static byte[] serializeSidecar(List<Entry> entries) {
        if (entries == null || entries.isEmpty()) return null;
        List<Entry> enriched = new ArrayList<>();
        for (Entry e : entries) {
            if (e == null) continue;
            boolean hasMeta = e.meta != null && !e.meta.isEmpty();
            boolean hasNbt = e.blockEntityNbt != null && e.blockEntityNbt.length > 0;
            if (hasMeta || hasNbt) enriched.add(e);
        }
        if (enriched.isEmpty()) return null;
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream(enriched.size() * 32);
             DataOutputStream out = new DataOutputStream(baos)) {
            out.writeInt(SIDECAR_MAGIC);
            out.writeInt(SIDECAR_VERSION);
            out.writeInt(enriched.size());
            for (Entry e : enriched) {
                out.writeInt(e.x);
                out.writeInt(e.y);
                out.writeInt(e.z);
                writeNullableUtf8(out, e.meta);
                writeNullableBytes(out, e.blockEntityNbt);
            }
            out.flush();
            return baos.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException("ByteArrayOutputStream write failed", impossible);
        }
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
            boolean hasMeta = e.meta != null && !e.meta.isEmpty();
            boolean hasNbt = e.blockEntityNbt != null && e.blockEntityNbt.length > 0;
            if (hasMeta || hasNbt) {
                sb.append('|');
                if (hasMeta) sb.append(e.meta);
            }
            if (hasNbt) {
                sb.append('|').append(Base64.getEncoder().encodeToString(e.blockEntityNbt));
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
            byte[] blockEntityNbt = null;
            int pipe = rest.indexOf('|');
            if (pipe >= 0) {
                blockId = rest.substring(0, pipe).trim();
                String suffix = rest.substring(pipe + 1);
                int secondPipe = suffix.indexOf('|');
                if (secondPipe >= 0) {
                    meta = suffix.substring(0, secondPipe);
                    String encoded = suffix.substring(secondPipe + 1);
                    if (!encoded.isBlank()) {
                        try {
                            blockEntityNbt = Base64.getDecoder().decode(encoded);
                        } catch (IllegalArgumentException badNbt) {
                            LOG.debug("ExplosionAffectedList: malformed entry (bad block-entity NBT): '{}'", entry);
                            blockEntityNbt = null;
                        }
                    }
                } else {
                    meta = suffix;
                }
                if (meta != null && meta.isEmpty()) meta = null;
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
            return new Entry(x, y, z, blockId, meta, blockEntityNbt);
        } catch (NumberFormatException e) {
            LOG.debug("ExplosionAffectedList: malformed entry (non-numeric coord): '{}'", entry);
            return null;
        }
    }

    private static Map<CoordKey, Entry> readSidecar(byte[] sidecar) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(sidecar))) {
            if (in.readInt() != SIDECAR_MAGIC) return Map.of();
            if (in.readInt() != SIDECAR_VERSION) return Map.of();
            int count = in.readInt();
            if (count < 0 || count > MAX_SIDECAR_ENTRIES) return Map.of();
            Map<CoordKey, Entry> out = new HashMap<>();
            for (int i = 0; i < count; i++) {
                int x = in.readInt();
                int y = in.readInt();
                int z = in.readInt();
                String meta = readNullableUtf8(in);
                byte[] nbt = readNullableBytes(in);
                out.put(new CoordKey(x, y, z), new Entry(x, y, z, "minecraft:air", meta, nbt));
            }
            return out;
        } catch (IOException | RuntimeException e) {
            LOG.debug("ExplosionAffectedList: malformed sidecar", e);
            return Map.of();
        }
    }

    private static void writeNullableUtf8(DataOutputStream out, String value) throws IOException {
        if (value == null || value.isEmpty()) {
            out.writeInt(-1);
            return;
        }
        writeNullableBytes(out, value.getBytes(StandardCharsets.UTF_8));
    }

    private static String readNullableUtf8(DataInputStream in) throws IOException {
        byte[] bytes = readNullableBytes(in);
        return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
    }

    private static void writeNullableBytes(DataOutputStream out, byte[] value) throws IOException {
        if (value == null || value.length == 0) {
            out.writeInt(-1);
            return;
        }
        out.writeInt(value.length);
        out.write(value);
    }

    private static byte[] readNullableBytes(DataInputStream in) throws IOException {
        int len = in.readInt();
        if (len < 0) return null;
        if (len > MAX_SIDECAR_FIELD_BYTES) throw new IOException("sidecar field too large: " + len);
        byte[] out = new byte[len];
        in.readFully(out);
        return out;
    }
}

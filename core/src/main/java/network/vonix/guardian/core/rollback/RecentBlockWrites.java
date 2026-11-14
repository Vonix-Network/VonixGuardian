package network.vonix.guardian.core.rollback;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Remembers block positions the attributed event layer has just recorded, so the
 * low-level write hook does not record them a second time.
 *
 * <p>The two layers overlap by design. A player breaking a block raises
 * {@code BlockEvent.BreakEvent} <em>and</em> results in a {@code setBlockState} call;
 * the event layer records it with the player's name, the write hook only knows the
 * position changed. Without this, every break in the game would appear twice — once
 * attributed, once as {@code UNKNOWN} — which is worse than missing it.</p>
 *
 * <p>TTL rather than exact bracketing on purpose: the event layer and the write happen
 * within microseconds of each other, but some paths defer (NeighborNotifyEvent
 * classifies natural transitions a tick late). A short window covers both without
 * requiring every one of the forty-odd handlers to be individually wrapped, which is
 * the kind of change that silently rots as handlers are added.</p>
 *
 * <p>Bounded and thread-confined in practice: the server thread is the only writer of
 * blocks. The map is access-ordered so a busy world evicts its coldest entries and the
 * structure cannot grow without limit.</p>
 */
public final class RecentBlockWrites {

    /** How long an attributed write suppresses the fallback hook for that position. */
    public static final long DEFAULT_TTL_MS = 2_000L;

    /** Hard cap on tracked positions; a full map evicts its oldest entry. */
    public static final int DEFAULT_CAPACITY = 8_192;

    private final long ttlMs;
    private final Map<Long, Long> seenAt;

    public RecentBlockWrites() {
        this(DEFAULT_TTL_MS, DEFAULT_CAPACITY);
    }

    /**
     * @param ttlMs      suppression window in milliseconds
     * @param capacity   maximum tracked positions
     */
    public RecentBlockWrites(long ttlMs, int capacity) {
        if (ttlMs <= 0L) {
            throw new IllegalArgumentException("ttlMs must be positive");
        }
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.ttlMs = ttlMs;
        final int cap = capacity;
        this.seenAt = new LinkedHashMap<>(cap, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, Long> eldest) {
                return size() > cap;
            }
        };
    }

    /**
     * Record that the attributed layer handled this position.
     *
     * @param worldId dimension key; may be {@code null}
     * @param x       block x
     * @param y       block y
     * @param z       block z
     * @param now     current time in milliseconds
     */
    public void record(String worldId, int x, int y, int z, long now) {
        seenAt.put(key(worldId, x, y, z), now);
    }

    /**
     * Whether the attributed layer covered this position recently enough that the
     * fallback must stay silent.
     *
     * @param worldId dimension key
     * @param x       block x
     * @param y       block y
     * @param z       block z
     * @param now     current time in milliseconds
     * @return {@code true} when an attributed row was already written for this position
     */
    public boolean recentlyAttributed(String worldId, int x, int y, int z, long now) {
        Long at = seenAt.get(key(worldId, x, y, z));
        if (at == null) {
            return false;
        }
        if (now - at > ttlMs) {
            seenAt.remove(key(worldId, x, y, z));
            return false;
        }
        return true;
    }

    /** Drop everything; used on shutdown and between tests. */
    public void clear() {
        seenAt.clear();
    }

    /** @return positions currently tracked, for diagnostics */
    public int size() {
        return seenAt.size();
    }

    /**
     * Pack world + position into one key. Block coordinates fit comfortably in the
     * low 54 bits; the world hash occupies the top.
     */
    private static long key(String worldId, int x, int y, int z) {
        long h = worldId == null ? 0L : (worldId.hashCode() & 0x3FFL);
        long px = ((long) x & 0x3FFFFFFL);
        long py = ((long) y & 0xFFFFFL);
        long pz = ((long) z & 0x3FFFFFFL);
        return (h << 54) | (px << 28) | (py << 12) | (pz & 0xFFFL);
    }
}

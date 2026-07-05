/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.core.event;

import network.vonix.guardian.core.event.EventSubmitter;
import network.vonix.guardian.core.event.Sentinel;
import network.vonix.guardian.core.rollback.ExplosionAffectedList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * Off-thread join + submit for {@code onExplosionDetonate} / {@code Explosion#finalizeExplosion}
 * pipelines (v1.3.0 W3).
 *
 * <h2>Problem</h2>
 *
 * <p>The pre-1.3.0 loader path performed the full per-affected-block iteration
 * synchronously on the server thread:</p>
 * <ol>
 *   <li>{@code level.getBlockState(pos)} per pos (chunk read).</li>
 *   <li>{@code Registry.BLOCK.getKey(block).toString()} per block (registry + String alloc).</li>
 *   <li>{@code StringBuilder.append(...)} of {@code x:y:z=id} per entry.</li>
 *   <li>{@code EventSubmitter.submitExplosion(...)} (queue enqueue).</li>
 * </ol>
 *
 * <p>A 5,000-block TNT chain therefore did 5k chunk reads plus 5k
 * {@link String} allocations on the tick — a documented server-thread stall
 * on FTBAE-scale servers.</p>
 *
 * <h2>Design: conservative off-thread join</h2>
 *
 * <p>The loader captures per affected block <em>only</em>:</p>
 * <ul>
 *   <li>The {@link net.minecraft.core.BlockPos} coordinates as three parallel
 *       {@code int[]} arrays. No new object per pos.</li>
 *   <li>The immutable block singleton reference as a {@code String[]}
 *       carrying the already-resolved {@code minecraft:name} id.</li>
 * </ul>
 *
 * <p><b>Thread-safety note (verified 2026-07-03).</b>
 * {@code ServerLevel#getBlockState} is <em>not</em> documented as thread-safe.
 * The Prism / Ledger / CoreProtect audit mods all capture states on the server
 * thread and only defer the {@link String} join off-thread. We follow the same
 * conservative discipline: {@link EventSubmitter#submitExplosion} is called
 * from the worker after joining, but the per-pos {@code getBlockState} and
 * registry-key lookup (already fast — the resolved id is a straight
 * {@code String} carrying no {@code BlockState} reference) both happen on the
 * server thread. What moves off-thread is <em>only</em> the
 * {@link StringBuilder} allocation, {@code append} loop, and the queue
 * enqueue.</p>
 *
 * <h2>Chunked EXPLOSION rows</h2>
 *
 * <p>If a single explosion has more than {@link #MAX_ENTRIES_PER_CHUNK} affected
 * blocks the worker splits the join into multiple {@code EXPLOSION} submits,
 * all sharing the same original center and source tag. This preserves audit
 * fidelity without any single row exceeding the storage-side VARCHAR(4096)
 * cap. W5's {@link network.vonix.guardian.core.rollback.RollbackEngine}
 * already iterates the affected-list per EXPLOSION row, so chunking is
 * transparent to rollback.</p>
 */
public final class ExplosionJoinWorker implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(ExplosionJoinWorker.class);

    /**
     * Maximum entries joined into a single EXPLOSION row's targetId. Sized so
     * that a worst-case per-entry footprint of ~40 chars
     * ({@code -30000:-64:-30000=modid:some_really_long_block_name,}) stays
     * comfortably under the VARCHAR(4096) storage cap.
     */
    public static final int MAX_ENTRIES_PER_CHUNK = 96;

    /**
     * Approximate byte cap per joined String (fallback in case a mod ships
     * an unusually long block id). Keeps the row payload well under 4096
     * bytes.
     */
    static final int MAX_JOIN_CHARS = 3800;

    private final ExecutorService worker;

    /** Wall-time (ns) sink for the join step. Read from tests / benchmarks. */
    private volatile long lastJoinNanos;

    /** Total explosions joined (successful submits, excluding chunks). Read from tests. */
    private volatile long joinedCount;

    /** Total chunks emitted (>= joinedCount). Read from tests / benchmarks. */
    private volatile long chunkCount;

    public ExplosionJoinWorker() {
        this(Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "VonixGuardian-ExplosionJoin");
            t.setDaemon(true);
            return t;
        }));
    }

    /** Test hook — inject a same-thread executor to observe join synchronously. */
    ExplosionJoinWorker(ExecutorService worker) {
        this.worker = Objects.requireNonNull(worker, "worker");
    }

    /**
     * Server-thread entry point. Copies its argument arrays into a task and
     * hands the join off to the worker. Returns immediately.
     *
     * @param submitter  the live {@link EventSubmitter} — captured, not resolved lazily
     * @param actorUuid  attribution actor (may be {@code null})
     * @param actorName  attribution actor name — {@link Sentinel#EXPLOSION} if {@code null}
     * @param worldId    world key (e.g. {@code minecraft:overworld})
     * @param cx         explosion center X
     * @param cy         explosion center Y
     * @param cz         explosion center Z
     * @param sourceTag  loader-computed source sentinel (e.g. {@code #tnt})
     * @param xs         per-affected-block X coords (copied before async handoff)
     * @param ys         per-affected-block Y coords
     * @param zs         per-affected-block Z coords
     * @param blockIds   per-affected-block resolved id strings
     * @param count      number of valid entries in the arrays
     */
    public void submit(EventSubmitter submitter,
                       UUID actorUuid, String actorName, String worldId,
                       int cx, int cy, int cz, String sourceTag,
                       int[] xs, int[] ys, int[] zs, String[] blockIds, int count) {
        submit(submitter, actorUuid, actorName, worldId, cx, cy, cz, sourceTag,
            xs, ys, zs, blockIds, null, null, count);
    }

    public void submit(EventSubmitter submitter,
                       UUID actorUuid, String actorName, String worldId,
                       int cx, int cy, int cz, String sourceTag,
                       int[] xs, int[] ys, int[] zs, String[] blockIds,
                       String[] blockMetas, byte[][] blockEntityNbts, int count) {
        if (submitter == null || count <= 0) return;
        int validCount = validCount(count, xs, ys, zs, blockIds);
        if (validCount != count) {
            LOG.warn("Guardian ExplosionJoinWorker: dropping explosion with invalid scratch lengths "
                    + "(requested={}, valid={}, xs={}, ys={}, zs={}, ids={})",
                    count, validCount, safeLength(xs), safeLength(ys), safeLength(zs), safeLength(blockIds));
            return;
        }

        // Snapshot to locals and copy the valid scratch-buffer range before
        // the async boundary. Loader cells reuse ThreadLocal arrays as soon as
        // submit() returns, so the worker must not capture the caller's array
        // references.
        final UUID au = actorUuid;
        final String an = actorName != null ? actorName : Sentinel.EXPLOSION;
        final String wid = worldId;
        final int fcx = cx, fcy = cy, fcz = cz;
        final String st = sourceTag != null ? sourceTag : Sentinel.EXPLOSION;
        final int[] fxs = Arrays.copyOf(xs, validCount);
        final int[] fys = Arrays.copyOf(ys, validCount);
        final int[] fzs = Arrays.copyOf(zs, validCount);
        final String[] fids = Arrays.copyOf(blockIds, validCount);
        final String[] fmetas = copyStrings(blockMetas, validCount);
        final byte[][] fnbts = copyBytes(blockEntityNbts, validCount);
        final int fn = validCount;
        try {
            worker.execute(() -> joinAndSubmit(submitter, au, an, wid, fcx, fcy, fcz, st,
                    fxs, fys, fzs, fids, fmetas, fnbts, fn));
        } catch (RejectedExecutionException rex) {
            LOG.warn("Guardian ExplosionJoinWorker: worker rejected explosion handoff for {} entries", fn, rex);
        }
    }

    private static int validCount(int requested, int[] xs, int[] ys, int[] zs, String[] ids) {
        int n = Math.min(requested, safeLength(xs));
        n = Math.min(n, safeLength(ys));
        n = Math.min(n, safeLength(zs));
        n = Math.min(n, safeLength(ids));
        return n;
    }

    private static int safeLength(int[] array) {
        return array != null ? array.length : 0;
    }

    private static int safeLength(String[] array) {
        return array != null ? array.length : 0;
    }

    private static String[] copyStrings(String[] source, int count) {
        if (source == null) return null;
        String[] out = new String[count];
        int n = Math.min(count, source.length);
        System.arraycopy(source, 0, out, 0, n);
        return out;
    }

    private static byte[][] copyBytes(byte[][] source, int count) {
        if (source == null) return null;
        byte[][] out = new byte[count][];
        int n = Math.min(count, source.length);
        for (int i = 0; i < n; i++) {
            out[i] = source[i] == null ? null : Arrays.copyOf(source[i], source[i].length);
        }
        return out;
    }

    private void joinAndSubmit(EventSubmitter s,
                               UUID au, String an, String wid,
                               int cx, int cy, int cz, String st,
                               int[] xs, int[] ys, int[] zs, String[] ids,
                               String[] metas, byte[][] nbts, int n) {
        try {
            long t0 = System.nanoTime();
            int chunkStart = 0;
            int chunkChunks = 0;
            while (chunkStart < n) {
                int chunkEnd = Math.min(chunkStart + MAX_ENTRIES_PER_CHUNK, n);
                StringBuilder sb = new StringBuilder(Math.min(MAX_JOIN_CHARS,
                        (chunkEnd - chunkStart) * 40));
                List<ExplosionAffectedList.Entry> sidecar = null;
                boolean first = true;
                int emitted = 0;
                for (int i = chunkStart; i < chunkEnd; i++) {
                    if (sb.length() > MAX_JOIN_CHARS) break;
                    String id = ids[i];
                    if (id == null || id.isEmpty()) continue;
                    if (!first) sb.append(',');
                    first = false;
                    sb.append(xs[i]).append(':').append(ys[i]).append(':').append(zs[i])
                      .append('=').append(id);
                    String meta = metas != null ? metas[i] : null;
                    byte[] nbt = nbts != null ? nbts[i] : null;
                    if ((meta != null && !meta.isEmpty()) || (nbt != null && nbt.length > 0)) {
                        if (sidecar == null) sidecar = new ArrayList<>();
                        sidecar.add(new ExplosionAffectedList.Entry(xs[i], ys[i], zs[i], id, meta, nbt));
                    }
                    emitted++;
                }
                if (emitted > 0) {
                    byte[] sidecarBytes = ExplosionAffectedList.serializeSidecar(sidecar);
                    s.submitExplosion(au, an, wid, cx, cy, cz, sb.toString(), st, sidecarBytes);
                    chunkChunks++;
                }
                chunkStart = chunkEnd;
            }
            lastJoinNanos = System.nanoTime() - t0;
            joinedCount++;
            chunkCount += chunkChunks;
        } catch (Throwable t) {
            LOG.warn("Guardian ExplosionJoinWorker: join failed for {} entries", n, t);
        }
    }

    public long lastJoinNanos() { return lastJoinNanos; }
    public long joinedCount()   { return joinedCount; }
    public long chunkCount()    { return chunkCount; }

    @Override
    public void close() {
        // v1.3.1 X6 (P2-1): give queued join tasks a chance to land in the write
        // queue before Guardian.shutdown() drains it. Without awaitTermination
        // an in-flight submitExplosion(...) on the join thread races the queue's
        // drainAndFlush and lands after dao.close() — the row is dropped.
        // 5 seconds mirrors AutoPurgeScheduler.shutdown(5_000L).
        worker.shutdown();
        try {
            if (!worker.awaitTermination(5L, java.util.concurrent.TimeUnit.SECONDS)) {
                LOG.warn("ExplosionJoinWorker: queued join tasks did not complete within 5s; forcing shutdownNow");
                worker.shutdownNow();
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            worker.shutdownNow();
        }
    }
}

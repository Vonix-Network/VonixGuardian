package network.vonix.threadedhorizons.common.notickvd;

import network.vonix.threadedhorizons.common.threading.scheduler.SchedulerThread;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import net.minecraft.server.level.Ticket;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.world.level.ChunkPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.threadly.concurrent.NoThreadScheduler;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

public class NoTickSystem {

    private static final Logger LOGGER = LoggerFactory.getLogger("Threaded Horizons NoTick");

    private final PlayerNoTickDistanceMap playerNoTickDistanceMap;
    private final NormalTicketDistanceMap normalTicketDistanceMap;
    private final DistanceManager chunkTicketManager;

    private final ConcurrentLinkedQueue<Runnable> pendingActions = new ConcurrentLinkedQueue<>();

    final NoThreadScheduler noThreadScheduler = new NoThreadScheduler();

    private final AtomicBoolean isTicking = new AtomicBoolean();
    private volatile LongSet noTickOnlyChunksSnapshot = LongSets.EMPTY_SET;

    public NoTickSystem(DistanceManager chunkTicketManager) {
        this.chunkTicketManager = chunkTicketManager;
        this.playerNoTickDistanceMap = new PlayerNoTickDistanceMap(chunkTicketManager, this);
        this.normalTicketDistanceMap = new NormalTicketDistanceMap(chunkTicketManager);
    }

    public void onTicketAdded(long position, Ticket<?> ticket) {
        this.pendingActions.add(() -> this.normalTicketDistanceMap.addTicket(position, ticket));
    }

    public void onTicketRemoved(long position, Ticket<?> ticket) {
        this.pendingActions.add(() -> this.normalTicketDistanceMap.removeTicket(position, ticket));
    }

    public void addPlayerSource(ChunkPos chunkPos) {
        this.pendingActions.add(() -> this.playerNoTickDistanceMap.addSource(chunkPos));
    }

    public void removePlayerSource(ChunkPos chunkPos) {
        this.pendingActions.add(() -> this.playerNoTickDistanceMap.removeSource(chunkPos));
    }

    public void setNoTickViewDistance(int viewDistance) {
        this.pendingActions.add(() -> this.playerNoTickDistanceMap.setViewDistance(viewDistance));
    }

    public void tick() {
        this.noThreadScheduler.tick(Throwable::printStackTrace);
        scheduleTick();
    }

    private void scheduleTick() {
        if (this.isTicking.compareAndSet(false, true)) {
            try {
                SchedulerThread.INSTANCE.execute(() -> {
                    boolean needsAnother = false;
                    try {
                        Runnable runnable;
                        while ((runnable = this.pendingActions.poll()) != null) {
                            try {
                                runnable.run();
                            } catch (Throwable t) {
                                LOGGER.error("No-tick pending action failed", t);
                            }
                        }

                        final boolean hasNormalTicketUpdates = this.normalTicketDistanceMap.update();
                        final boolean hasNoTickUpdates = this.playerNoTickDistanceMap.update();
                        if (hasNormalTicketUpdates || hasNoTickUpdates) {
                            final LongSet noTickChunks = this.playerNoTickDistanceMap.getChunks();
                            final LongSet normalChunks = this.normalTicketDistanceMap.getChunks();
                            final LongOpenHashSet longs = new LongOpenHashSet(noTickChunks.size() * 3 / 2);
                            final LongIterator iterator = noTickChunks.iterator();
                            while (iterator.hasNext()) {
                                final long chunk = iterator.nextLong();
                                if (normalChunks.contains(chunk)) continue;
                                longs.add(chunk);
                            }
                            this.noTickOnlyChunksSnapshot = LongSets.unmodifiable(longs);
                            needsAnother = true;
                        }
                    } catch (Throwable t) {
                        LOGGER.error("No-tick update failed", t);
                    } finally {
                        this.isTicking.set(false);
                    }
                    if (needsAnother || !this.pendingActions.isEmpty()) {
                        scheduleTick();
                    }
                });
            } catch (RuntimeException scheduleFailure) {
                this.isTicking.set(false);
                throw scheduleFailure;
            }
        }
    }

    public void runPurge(long age) {
        this.pendingActions.add(() -> {
            this.normalTicketDistanceMap.purge(age);
            this.playerNoTickDistanceMap.runPendingTicketUpdates();
        });
    }

    public LongSet getNoTickOnlyChunksSnapshot() {
        return this.noTickOnlyChunksSnapshot;
    }

    public int getPendingNoTickTicketUpdatesCount() {
        return this.playerNoTickDistanceMap.getPendingTicketUpdatesCount();
    }
}

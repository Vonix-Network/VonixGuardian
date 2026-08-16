package network.vonix.threadedhorizons.common.notickvd;

import network.vonix.threadedhorizons.common.config.ThreadedHorizonsConfig;
import network.vonix.threadedhorizons.mixin.access.IChunkTicketManager;
import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.longs.Long2BooleanLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2BooleanMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.objects.ObjectBidirectionalIterator;
import it.unimi.dsi.fastutil.objects.ObjectSet;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.util.Mth;
import net.minecraft.server.level.ChunkTracker;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;

public class PlayerNoTickDistanceMap extends ChunkTracker {

    private static final Logger LOGGER = LogUtils.getLogger();
    public static final TicketType<ChunkPos> TICKET_TYPE = TicketType.create("threadedhorizons_no_tick_vd", Comparator.comparingLong(ChunkPos::toLong));

    private static final int MAX_TICKET_UPDATES_PER_TICK = ThreadedHorizonsConfig.noTickViewDistanceConfig.updatesPerTick;

    private final LongSet sourceChunks = new LongOpenHashSet();
    private final Long2IntOpenHashMap distanceFromNearestPlayer = new Long2IntOpenHashMap();
    private final Long2BooleanLinkedOpenHashMap pendingTicketUpdates = new Long2BooleanLinkedOpenHashMap();
    private final LongOpenHashSet managedChunkTickets = new LongOpenHashSet();

    private final DistanceManager chunkTicketManager;
    private final NoTickSystem noTickSystem;
    private volatile int viewDistance;
    private volatile int pendingTicketUpdatesCount = 0; // for easier access concurrently

    public PlayerNoTickDistanceMap(DistanceManager chunkTicketManager, NoTickSystem noTickSystem) {
        super(251, 16, 256);
        this.chunkTicketManager = chunkTicketManager;
        this.noTickSystem = noTickSystem;
        this.distanceFromNearestPlayer.defaultReturnValue(251);
        this.setViewDistance(12);
    }

    @Override
    protected int getLevelFromSource(long chunkPos) {
        final ObjectSet<ServerPlayer> players = ((IChunkTicketManager) chunkTicketManager).getPlayersByChunkPos().get(chunkPos);
        return players != null && !players.isEmpty() ? 249 - viewDistance : Integer.MAX_VALUE;
    }

    @Override
    protected int getLevel(long chunkPos) {
        return this.distanceFromNearestPlayer.get(chunkPos);
    }

    @Override
    protected void setLevel(long chunkPos, int level) {
        if (level > 249) {
            if (this.distanceFromNearestPlayer.containsKey(chunkPos)) {
                pendingTicketUpdates.put(chunkPos, false);
                this.distanceFromNearestPlayer.remove(chunkPos);
            }
        } else {
            if (!this.distanceFromNearestPlayer.containsKey(chunkPos)) {
                pendingTicketUpdates.put(chunkPos, true);
            }
            this.distanceFromNearestPlayer.put(chunkPos, level);
        }
    }

    public void addSource(ChunkPos chunkPos) {
        this.update(chunkPos.toLong(), 249 - this.viewDistance, true);
        this.sourceChunks.add(chunkPos.toLong());
    }

    public void removeSource(ChunkPos chunkPos) {
        this.update(chunkPos.toLong(), Integer.MAX_VALUE, false);
        this.sourceChunks.remove(chunkPos.toLong());
    }

    public boolean update() {
        final int pendingRawUpdateCount = this.getQueueSize();
        if (pendingRawUpdateCount == 0) return false;
        final boolean hasLightWork = this.runUpdates(Integer.MAX_VALUE) != Integer.MAX_VALUE;
        if (hasLightWork) {
            // resort updates
            final ArrayList<Long2BooleanMap.Entry> entries = new ArrayList<>(this.pendingTicketUpdates.long2BooleanEntrySet());
            entries.sort(Comparator.comparingInt(o -> this.distanceFromNearestPlayer.get(o.getLongKey())));
            for (Long2BooleanMap.Entry entry : entries) {
                this.pendingTicketUpdates.getAndMoveToLast(entry.getLongKey());
            }
        }
        this.pendingTicketUpdatesCount = this.pendingTicketUpdates.size();
        return true;
    }

    void runPendingTicketUpdates() {
        final ObjectBidirectionalIterator<Long2BooleanMap.Entry> iterator = this.pendingTicketUpdates.long2BooleanEntrySet().iterator();
        int i = 0;
        while (iterator.hasNext() && i <= MAX_TICKET_UPDATES_PER_TICK) {
            final Long2BooleanMap.Entry entry = iterator.next();
            final long chunkPos = entry.getLongKey();
            ChunkPos pos = new ChunkPos(chunkPos);
            if (entry.getBooleanValue()) {
                if (this.managedChunkTickets.add(chunkPos)) {
                    this.noTickSystem.noThreadScheduler.execute(() -> this.chunkTicketManager.addTicket(TICKET_TYPE, pos, 33, pos));
                    i ++;
                }
            } else {
                if (this.managedChunkTickets.remove(chunkPos)) {
                    this.noTickSystem.noThreadScheduler.execute(() -> this.chunkTicketManager.removeTicket(TICKET_TYPE, pos, 33, pos));
                    i ++;
                }
            }
            iterator.remove();
        }
        this.pendingTicketUpdatesCount = this.pendingTicketUpdates.size();
    }

    public void setViewDistance(int viewDistance) {
        this.viewDistance = Mth.clamp(viewDistance, 3, 249);
        sourceChunks.forEach((long value) -> {
            removeSource(new ChunkPos(value));
            addSource(new ChunkPos(value));
        });
    }

    public int getPendingTicketUpdatesCount() {
        return this.pendingTicketUpdatesCount;
    }

    public LongSet getChunks() {
        return managedChunkTickets;
    }

}

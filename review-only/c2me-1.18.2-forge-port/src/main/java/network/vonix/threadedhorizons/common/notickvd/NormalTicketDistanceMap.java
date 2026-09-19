package network.vonix.threadedhorizons.common.notickvd;

import network.vonix.threadedhorizons.mixin.access.IChunkTicket;
import network.vonix.threadedhorizons.mixin.access.IChunkTicketManager;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.minecraft.server.level.Ticket;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.util.SortedArraySet;
import net.minecraft.server.level.ChunkTracker;

public class NormalTicketDistanceMap extends ChunkTracker {
    private final DistanceManager chunkTicketManager;
    private final Long2IntOpenHashMap distanceMap = new Long2IntOpenHashMap();
    private final Long2ObjectOpenHashMap<SortedArraySet<Ticket<?>>> ticketsByPosition = new Long2ObjectOpenHashMap<>();

    public NormalTicketDistanceMap(DistanceManager chunkTicketManager) {
        super(33 + 2, 16, 256);
        this.chunkTicketManager = chunkTicketManager;
        distanceMap.defaultReturnValue(33 + 2);
    }

    @Override
    protected int getLevelFromSource(long id) {
        SortedArraySet<Ticket<?>> sortedArraySet = ((IChunkTicketManager) chunkTicketManager).getTicketsByPosition().get(id);
        if (sortedArraySet != null) {
            if (sortedArraySet.isEmpty()) return Integer.MAX_VALUE;
            for (Ticket<?> next : sortedArraySet) {
                if (next.getType() == PlayerNoTickDistanceMap.TICKET_TYPE) continue;
                return next.getTicketLevel();
            }
        }
        return Integer.MAX_VALUE;
    }

    @Override
    protected int getLevel(long id) {
        return distanceMap.get(id);
    }

    @Override
    protected void setLevel(long id, int level) {
        if (level > 33) {
            distanceMap.remove(id);
        } else {
            distanceMap.put(id, level);
        }
    }

    private static int getLevel(SortedArraySet<Ticket<?>> sortedArraySet) {
        return !sortedArraySet.isEmpty() ? sortedArraySet.first().getTicketLevel() : Integer.MAX_VALUE;
    }

    public void addTicket(long position, Ticket<?> ticket) {
        if (ticket.getType() == PlayerNoTickDistanceMap.TICKET_TYPE) return;
        SortedArraySet<Ticket<?>> sortedArraySet = this.getTicketSet(position);
        int i = getLevel(sortedArraySet);
        sortedArraySet.add(ticket);
        if (ticket.getTicketLevel() < i) {
            this.update(position, ticket.getTicketLevel(), true);
        }
    }

    public void removeTicket(long pos, Ticket<?> ticket) {
        if (ticket.getType() == PlayerNoTickDistanceMap.TICKET_TYPE) return;
        SortedArraySet<Ticket<?>> sortedArraySet = this.getTicketSet(pos);
        sortedArraySet.remove(ticket);

        if (sortedArraySet.isEmpty()) {
            this.ticketsByPosition.remove(pos);
        }

        this.update(pos, getLevel(sortedArraySet), false);
    }

    public void purge(long age) {
        final ObjectIterator<Long2ObjectMap.Entry<SortedArraySet<Ticket<?>>>> iterator = this.ticketsByPosition.long2ObjectEntrySet().fastIterator();

        while (iterator.hasNext()) {
            final Long2ObjectMap.Entry<SortedArraySet<Ticket<?>>> entry = iterator.next();
            final boolean isModified = entry.getValue().removeIf(chunkTicket -> ((IChunkTicket) (Object) chunkTicket).invokeIsExpired(age));
            if (isModified) {
                this.update(entry.getLongKey(), getLevel(entry.getValue()), false);
            }
            if (entry.getValue().isEmpty()) {
                iterator.remove();
            }
        }
    }

    public SortedArraySet<Ticket<?>> getTicketSet(long pos) {
        return this.ticketsByPosition.computeIfAbsent(pos, (l) -> SortedArraySet.create(4));
    }

    public boolean update() {
        return Integer.MAX_VALUE - this.runUpdates(Integer.MAX_VALUE) != 0;
    }

    public LongSet getChunks() {
        return this.distanceMap.keySet();
    }
}

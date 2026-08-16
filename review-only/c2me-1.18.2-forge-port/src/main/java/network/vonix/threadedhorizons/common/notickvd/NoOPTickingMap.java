package network.vonix.threadedhorizons.common.notickvd;

import net.minecraft.server.level.Ticket;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.server.level.TickingTracker;

import java.util.function.LongPredicate;

public class NoOPTickingMap extends TickingTracker {

    @Override
    public void addTicket(long l, Ticket<?> chunkTicket) {
    }

    @Override
    public void removeTicket(long l, Ticket<?> chunkTicket) {
    }

    @Override
    public <T> void addTicket(TicketType<T> chunkTicketType, ChunkPos chunkPos, int i, T object) {
    }

    @Override
    public <T> void removeTicket(TicketType<T> chunkTicketType, ChunkPos chunkPos, int i, T object) {
    }

    @Override
    public void replacePlayerTicketsLevel(int i) {
    }

    @Override
    protected int getLevelFromSource(long id) {
        return Integer.MAX_VALUE;
    }

    @Override
    public int getLevel(ChunkPos chunkPos) {
        return 0;
    }

    @Override
    protected int getLevel(long id) {
        return 0;
    }

    @Override
    protected void setLevel(long id, int level) {
    }

    @Override
    public void runAllUpdates() {
    }

    @Override
    public String getTicketDebugString(long l) {
        return "no-op";
    }

    @Override
    public void update(long chunkPos, int distance, boolean decrease) {
    }

    @Override
    public int getQueueSize() {
        return 0;
    }
}

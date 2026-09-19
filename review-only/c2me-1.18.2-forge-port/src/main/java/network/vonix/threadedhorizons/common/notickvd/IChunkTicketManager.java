package network.vonix.threadedhorizons.common.notickvd;

import it.unimi.dsi.fastutil.longs.LongSet;

public interface IChunkTicketManager {

    LongSet getNoTickOnlyChunks();

    int getNoTickPendingTicketUpdates();

}

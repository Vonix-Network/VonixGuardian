package network.vonix.threadedhorizons.mixin.notickvd;

import network.vonix.threadedhorizons.common.notickvd.IChunkTicketManager;
import network.vonix.threadedhorizons.common.notickvd.NoOPTickingMap;
import network.vonix.threadedhorizons.common.notickvd.NoTickSystem;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.Ticket;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.TickingTracker;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(DistanceManager.class)
public class MixinChunkTicketManager implements IChunkTicketManager {

    @Shadow private long ticketTickCounter;
    @Mutable
    @Shadow @Final private TickingTracker tickingTicketsTracker;
    @Shadow @Final private DistanceManager.PlayerTicketTracker playerTicketManager;

    @Unique
    private NoTickSystem noTickSystem;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void onInit(CallbackInfo ci) {
        this.noTickSystem = new NoTickSystem((DistanceManager) (Object) this);
        this.tickingTicketsTracker = new NoOPTickingMap();
    }

    @Inject(method = "addPlayer", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/DistanceManager$FixedPlayerDistanceChunkTracker;update(JIZ)V", ordinal = 0, shift = At.Shift.AFTER))
    private void onHandleChunkEnter(SectionPos pos, ServerPlayer player, CallbackInfo ci) {
        this.noTickSystem.addPlayerSource(pos.chunk());
    }

    @Inject(method = "removePlayer", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/DistanceManager$FixedPlayerDistanceChunkTracker;update(JIZ)V", ordinal = 0, shift = At.Shift.AFTER))
    private void onHandleChunkLeave(SectionPos pos, ServerPlayer player, CallbackInfo ci) {
        this.noTickSystem.removePlayerSource(pos.chunk());
    }

    @Inject(method = "purgeStaleTickets", at = @At("RETURN"))
    private void onPurge(CallbackInfo ci) {
        this.noTickSystem.runPurge(this.ticketTickCounter);
        this.noTickSystem.tick();
    }

    @Inject(method = "addTicket(JLnet/minecraft/server/level/Ticket;)V", at = @At("RETURN"))
    private void onAddTicket(long position, Ticket<?> ticket, CallbackInfo ci) {
//        if (ticket.getType() != TicketType.UNKNOWN) System.err.printf("Added ticket (%s) at %s\n", ticket, new ChunkPos(position));
        this.noTickSystem.onTicketAdded(position, ticket);
    }

    @Inject(method = "removeTicket(JLnet/minecraft/server/level/Ticket;)V", at = @At("RETURN"))
    private void onRemoveTicket(long pos, Ticket<?> ticket, CallbackInfo ci) {
//        if (ticket.getType() != TicketType.UNKNOWN) System.err.printf("Removed ticket (%s) at %s\n", ticket, new ChunkPos(pos));
        this.noTickSystem.onTicketRemoved(pos, ticket);
    }

    /**
     * @author ishland
     * @reason remap setSimulationDistance to the normal one
     */
    @Overwrite
    public void updateSimulationDistance(int i) {
        this.playerTicketManager.updateViewDistance(i);
    }

    /**
     * @author ishland
     * @reason remap updateViewDistance to no-tick one
     */
    @Overwrite
    protected void updatePlayerTickets(int viewDistance) {
        this.noTickSystem.setNoTickViewDistance(viewDistance);
    }

    @Override
    @Unique
    public LongSet getNoTickOnlyChunks() {
        return this.noTickSystem.getNoTickOnlyChunksSnapshot();
    }

    @Override
    @Unique
    public int getNoTickPendingTicketUpdates() {
        return this.noTickSystem.getPendingNoTickTicketUpdatesCount();
    }
}

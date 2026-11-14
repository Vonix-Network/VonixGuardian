/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_18_2.forge;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import network.vonix.guardian.core.rollback.RollbackResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Server half of the optional VonixGuardian client companion channel.
 *
 * <p>Chat output is the only delivery path for a rollback result, and chat is a
 * bad one for anything structured: an operator reading a large lookup scrolls, and
 * a GUI cannot be built from coloured text. This channel ships the same result to
 * a client mod that can present it properly.</p>
 *
 * <p><b>The client mod is optional and absent by default.</b> Every send is
 * attempted only for a player whose client advertised the channel, so a vanilla
 * client — or one running an older companion — costs nothing and receives no
 * packet. If the channel were unconditional, a version mismatch would show up as
 * a client-side disconnect instead of a missing GUI, which is a far worse failure
 * for an audit tool that an admin needs during an incident.</p>
 *
 * <p><b>This class is the server side only.</b> It deliberately contains no client
 * classes: registering a client handler here would load client-only types on a
 * dedicated server and crash it at boot.</p>
 */
public final class GuardianChannel {

    private static final Logger LOG = LoggerFactory.getLogger(GuardianChannel.class);

    /** Channel id. Bumping the version below is what marks a companion incompatible. */
    public static final ResourceLocation ID =
        new ResourceLocation(VonixGuardianForge.MOD_ID, "results");

    /** Protocol version. A client on a different version is silently skipped. */
    public static final String PROTOCOL = "1";

    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
        ID,
        () -> PROTOCOL,
        PROTOCOL::equals,
        PROTOCOL::equals);

    private GuardianChannel() {
    }

    /** Register payloads. Called once during mod construction. */
    public static void register() {
        // Payload ids must be stable: a companion built against an older id would
        // decode a different message into the same type.
        CHANNEL.messageBuilder(RollbackResultMessage.class, 0)
            .encoder(RollbackResultMessage::encode)
            .decoder(RollbackResultMessage::decode)
            .consumer(RollbackResultMessage::handle)
            .add();
        LOG.info("VonixGuardian channel {} registered (protocol {})", ID, PROTOCOL);
    }

    /**
     * Whether {@code player} may receive frames on this channel.
     *
     * @param player recipient
     * @return {@code true} only when the client advertised a compatible channel
     */
    public static boolean canReceive(ServerPlayer player) {
        if (player == null) {
            return false;
        }
        try {
            return CHANNEL.isRemotePresent(player.connection.connection);
        } catch (RuntimeException ex) {
            // A connection still handshaking has no channel registry yet.
            return false;
        }
    }

    /**
     * Deliver a result to one player if their client can accept it.
     *
     * @param player recipient
     * @param result the result to send
     * @param lines  rendered outcome lines for the client to display
     * @return {@code true} when a packet was sent
     */
    public static boolean send(ServerPlayer player, RollbackResult result, List<String> lines) {
        if (!canReceive(player) || result == null) {
            return false;
        }
        try {
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                RollbackResultMessage.from(result, lines));
            return true;
        } catch (RuntimeException ex) {
            // Never let a companion failure break the command that produced it.
            LOG.warn("VonixGuardian: could not deliver result to {}: {}",
                player.getGameProfile().getName(), ex.toString());
            return false;
        }
    }

    /**
     * A structured result frame.
     *
     * <p>Flat fields rather than a serialised core object: the wire format must stay
     * stable across a companion that lags the server mod, and a core record gaining
     * a field must not silently corrupt an older client's decode.</p>
     */
    public record RollbackResultMessage(
        boolean preview,
        String mode,
        String status,
        int planned,
        int dispatched,
        int skipped,
        int failed,
        int repairRequired,
        List<String> lines
    ) {

        /**
         * Build a frame from a result.
         *
         * @param r     the result to send
         * @param lines rendered outcome lines, already worded for an operator
         * @return the wire frame
         */
        static RollbackResultMessage from(RollbackResult r, List<String> lines) {
            return new RollbackResultMessage(
                r.preview(),
                r.mode().name(),
                r.status().name(),
                r.plannedSteps(),
                r.dispatchedSteps(),
                r.skippedCount(),
                r.failedCount(),
                r.repairRequiredCount(),
                lines == null ? List.of() : List.copyOf(lines));
        }

        void encode(net.minecraft.network.FriendlyByteBuf buf) {
            buf.writeBoolean(preview);
            buf.writeUtf(mode, 32);
            buf.writeUtf(status, 32);
            buf.writeVarInt(planned);
            buf.writeVarInt(dispatched);
            buf.writeVarInt(skipped);
            buf.writeVarInt(failed);
            buf.writeVarInt(repairRequired);
            buf.writeVarInt(lines.size());
            for (String line : lines) {
                buf.writeUtf(line, 1024);
            }
        }

        static RollbackResultMessage decode(net.minecraft.network.FriendlyByteBuf buf) {
            boolean preview = buf.readBoolean();
            String mode = buf.readUtf(32);
            String status = buf.readUtf(32);
            int planned = buf.readVarInt();
            int dispatched = buf.readVarInt();
            int skipped = buf.readVarInt();
            int failed = buf.readVarInt();
            int repairRequired = buf.readVarInt();
            int n = buf.readVarInt();
            List<String> lines = new java.util.ArrayList<>(Math.min(n, 256));
            for (int i = 0; i < n && i < 256; i++) {
                lines.add(buf.readUtf(1024));
            }
            return new RollbackResultMessage(preview, mode, status, planned, dispatched,
                skipped, failed, repairRequired, lines);
        }

        void handle(java.util.function.Supplier<net.minecraftforge.network.NetworkEvent.Context> ctx) {
            // No client handler ships here on purpose: this class is loaded on a
            // dedicated server, where client classes do not exist.
            var context = ctx.get();
            context.setPacketHandled(true);
        }
    }
}

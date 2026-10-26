/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.hytale.menu;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import network.vonix.guardian.core.Guardian;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * {@code /guardian menu} (alias {@code /vg menu}). Opens a custom UI page.
 * The page is opened on the player's world thread, the same hop
 * {@code HelpCommand.openHelpUI} uses before {@code PageManager.openCustomPage}.
 */
public final class MenuCommand extends AbstractCommand {

    private final MenuHost host;

    public MenuCommand(MenuHost host) {
        super("menu", "Open the VonixGuardian menu.");
        if (host == null) {
            throw new IllegalArgumentException("host is required");
        }
        this.host = host;
    }

    @Override
    protected CompletableFuture<Void> execute(CommandContext context) {
        if (context == null || !context.isPlayer()) {
            reply(context, "[VonixGuardian] Menu must be run by a player.");
            return done();
        }
        Ref<EntityStore> ref = context.senderAsPlayerRef();
        if (ref == null || !ref.isValid()) {
            reply(context, "[VonixGuardian] Menu must be run by a player who is in a world.");
            return done();
        }
        UUID uuid;
        try {
            uuid = context.sender() == null ? null : context.sender().getUuid();
        } catch (RuntimeException ex) {
            host.logger().warn(Guardian.MARKER, "Menu sender uuid unavailable", ex);
            uuid = null;
        }
        String denial = host.gate(uuid, MenuHost.USE_NODE);
        if (denial != null) {
            reply(context, "[VonixGuardian] " + denial);
            return done();
        }
        Store<EntityStore> store = ref.getStore();
        World world = worldOf(store);
        if (store == null || world == null) {
            reply(context, "[VonixGuardian] Menu could not find your world. Nothing was opened.");
            return done();
        }
        world.execute(() -> open(context, ref, store));
        return done();
    }

    private void open(CommandContext context, Ref<EntityStore> ref, Store<EntityStore> store) {
        try {
            if (!ref.isValid()) {
                reply(context, "[VonixGuardian] Menu must be run by a player who is in a world.");
                return;
            }
            Player player = store.getComponent(ref, Player.getComponentType());
            PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
            if (player == null || playerRef == null) {
                reply(context, "[VonixGuardian] Menu could not find your player. Nothing was opened.");
                return;
            }
            player.getPageManager().openCustomPage(ref, store, new MenuPage(playerRef, host));
        } catch (RuntimeException ex) {
            host.logger().warn(Guardian.MARKER, "Menu failed to open", ex);
            reply(context, "[VonixGuardian] Menu failed to open.");
        }
    }

    private static World worldOf(Store<EntityStore> store) {
        if (store == null) {
            return null;
        }
        Object data = store.getExternalData();
        if (!(data instanceof EntityStore entities)) {
            return null;
        }
        return entities.getWorld();
    }

    private static void reply(CommandContext context, String text) {
        if (context == null) {
            return;
        }
        try {
            context.sendMessage(Message.raw(text));
        } catch (RuntimeException ignored) {
            // The page could not open and chat delivery failed too.
        }
    }

    private static CompletableFuture<Void> done() {
        return CompletableFuture.completedFuture(null);
    }
}

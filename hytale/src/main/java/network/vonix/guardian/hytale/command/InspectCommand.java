/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.hytale.command;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import network.vonix.guardian.core.command.CommandSpec;
import org.slf4j.Logger;

import java.util.concurrent.CompletableFuture;

/**
 * {@code /guardian inspect}. Toggles {@link InspectMode} only. Click capture
 * is not registered here.
 */
final class InspectCommand extends GuardianSubcommand {

    InspectCommand(CommandRuntime runtime, Logger logger) {
        super(CommandSpec.INSPECT, "Toggle inspection mode.",
                false, runtime, logger, CommandSpec.permissionNode(CommandSpec.INSPECT), false);
        addAliases("i");
    }

    @Override
    protected CompletableFuture<Void> execute(CommandContext context) {
        if (!admit(context)) {
            return done();
        }
        Ref<EntityStore> ref = context.senderAsPlayerRef();
        if (!context.isPlayer() || ref == null || !ref.isValid()) {
            runtime.reply(context, runtime.error("[VonixGuardian] Inspect must be run by a player."));
            return done();
        }
        CommandRuntime.Actor actor = runtime.capture(context);
        if (!requireIdentity(context, actor)) {
            return done();
        }
        if (InspectMode.toggle(actor.uuid())) {
            runtime.reply(context, runtime.success(
                    "[VonixGuardian] Inspect mode enabled. Click lookup is not wired on this server yet."));
        } else {
            runtime.reply(context, runtime.muted("[VonixGuardian] Inspect mode disabled."));
        }
        return done();
    }
}

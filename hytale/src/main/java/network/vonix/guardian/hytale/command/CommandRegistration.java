/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.hytale.command;

import com.hypixel.hytale.server.core.command.system.CommandRegistry;
import network.vonix.guardian.core.Guardian;
import network.vonix.guardian.hytale.spi.GuardianServices;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bootstrap entry point. Call once, on the plugin {@link CommandRegistry}, so
 * Hytale can attach the plugin owner and its base permission.
 */
public final class CommandRegistration {

    private CommandRegistration() {
    }

    public static void register(GuardianServices services, CommandRegistry registry) {
        if (services == null || registry == null) {
            throw new IllegalArgumentException("services and registry are required");
        }
        Logger logger = LoggerFactory.getLogger(GuardianCommand.class);
        GuardianCommand root = new GuardianCommand(services, logger);
        com.hypixel.hytale.server.core.command.system.CommandRegistration registered =
                registry.registerCommand(root);
        if (registered == null) {
            logger.error(Guardian.MARKER, "Hytale command registry rejected /guardian");
            throw new IllegalStateException("Hytale command registry rejected /guardian");
        }
    }
}

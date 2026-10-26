/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.hytale.menu;

import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.command.system.CommandManager;
import com.hypixel.hytale.server.core.command.system.CommandOwner;
import com.hypixel.hytale.server.core.command.system.CommandRegistry;
import com.hypixel.hytale.server.core.command.system.exceptions.GeneralCommandException;
import network.vonix.guardian.core.Guardian;
import network.vonix.guardian.hytale.spi.GuardianServices;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.util.Map;

/**
 * Attaches {@code menu} under the already-registered {@code /guardian}
 * command (alias {@code /vg}). The parent calls this after
 * {@code CommandRegistration.register}. This class does not register a
 * second root.
 *
 * <p>{@code addSubCommand} throws once {@code hasBeenRegistered} is true.
 * The flag is cleared for the add, then restored. The child is then given
 * the parent's owner and {@code completeRegistration} so its generated
 * permission node is registered. Dispatch reads the live subcommand map, so
 * the parent's abbreviation map does not need a rebuild.
 */
public final class MenuRegistration {

    private static volatile MenuHost host;

    private MenuRegistration() {
    }

    public static void register(GuardianServices services, CommandRegistry registry) {
        if (services == null || registry == null) {
            throw new IllegalArgumentException("services and registry are required");
        }
        Logger logger = LoggerFactory.getLogger(MenuCommand.class);
        CommandManager manager = CommandManager.get();
        if (manager == null) {
            throw new IllegalStateException(
                    "CommandManager is unavailable. Call CommandRegistration.register before MenuRegistration.register.");
        }
        AbstractCommand guardian = findGuardian(manager);
        if (guardian == null) {
            throw new IllegalStateException(
                    "Command /guardian is not registered. Call CommandRegistration.register before MenuRegistration.register.");
        }
        CommandOwner owner = guardian.getOwner();
        if (owner == null) {
            throw new IllegalStateException(
                    "Command /guardian has no owner. Call CommandRegistration.register before MenuRegistration.register.");
        }
        AbstractCommand existing = guardian.getSubCommand("menu");
        if (existing != null) {
            if (existing.getOwner() == null) {
                existing.setOwner(owner);
            }
            finish(existing);
            logger.info(Guardian.MARKER, "VonixGuardian menu subcommand is already registered");
            return;
        }
        MenuHost menuHost = host;
        if (menuHost == null) {
            menuHost = new MenuHost(services, logger, owner);
            host = menuHost;
        }
        MenuCommand menu = new MenuCommand(menuHost);
        attach(guardian, menu);
        menu.setOwner(owner);
        finish(menu);
        logger.info(Guardian.MARKER, "Registered /guardian menu");
    }

    private static void finish(AbstractCommand menu) {
        try {
            menu.completeRegistration();
        } catch (GeneralCommandException ex) {
            throw new IllegalStateException("VonixGuardian menu subcommand failed to register", ex);
        }
    }

    private static void attach(AbstractCommand guardian, MenuCommand menu) {
        Field flag;
        try {
            flag = AbstractCommand.class.getDeclaredField("hasBeenRegistered");
            flag.setAccessible(true);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(
                    "Cannot attach /guardian menu because hasBeenRegistered is inaccessible", ex);
        }
        boolean previous;
        try {
            previous = flag.getBoolean(guardian);
        } catch (IllegalAccessException ex) {
            throw new IllegalStateException(
                    "Cannot read /guardian registration state", ex);
        }
        try {
            flag.setBoolean(guardian, false);
            guardian.addSubCommand(menu);
        } catch (IllegalAccessException ex) {
            throw new IllegalStateException("Cannot clear /guardian registration state", ex);
        } finally {
            try {
                flag.setBoolean(guardian, previous);
            } catch (IllegalAccessException ex) {
                throw new IllegalStateException("Cannot restore /guardian registration state", ex);
            }
        }
    }

    private static AbstractCommand findGuardian(CommandManager manager) {
        AbstractCommand byName = manager.resolveCommand("guardian");
        if (isGuardian(byName)) {
            return byName;
        }
        AbstractCommand byAlias = manager.resolveCommand("vg");
        if (isGuardian(byAlias)) {
            return byAlias;
        }
        Map<String, AbstractCommand> registered = manager.getCommandRegistration();
        if (registered == null) {
            return null;
        }
        for (AbstractCommand command : registered.values()) {
            if (isGuardian(command)) {
                return command;
            }
        }
        return null;
    }

    private static boolean isGuardian(AbstractCommand command) {
        return command != null && command.getName() != null
                && "guardian".equalsIgnoreCase(command.getName());
    }
}

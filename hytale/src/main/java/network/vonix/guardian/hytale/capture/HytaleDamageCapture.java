/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.hytale.capture;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.event.events.ecs.DamageBlockEvent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import org.joml.Vector3i;

import java.util.logging.Level;

/**
 * Block-damage attribution. {@link DamageBlockEvent} is invoked on the entity
 * that is damaging the block, but the event object itself has no player, entity
 * ref, or damage source. The fields are the held item, the target position, the
 * block type, and two damage amounts. A held item is not a player.
 *
 * <p>No row is submitted. Subscribing would also run on every mining tick and
 * could be mistaken for a second break log next to {@code BreakBlockEvent}.
 */
public final class HytaleDamageCapture {

    private HytaleDamageCapture() {
    }

    /**
     * @return {@code false}. {@link DamageBlockEvent} does not identify a player.
     */
    public static boolean hasProvenPlayerCauser(DamageBlockEvent event) {
        if (event == null) {
            return false;
        }
        return playerFromEventFields(event.getItemInHand(), event.getTargetBlock(), event.getBlockType(),
                event.getCurrentDamage(), event.getDamage());
    }

    static void reportUnattributed(HytaleLogger logger) {
        if (logger == null) {
            return;
        }
        logger.at(Level.INFO).log(
                "DamageBlockEvent has no player causer (itemInHand, targetBlock, blockType, currentDamage, damage). "
                        + "Block-damage attribution is not recorded.");
    }

    /**
     * The complete public payload of {@link DamageBlockEvent}. None of it is a
     * player UUID, name, or entity source.
     */
    private static boolean playerFromEventFields(ItemStack itemInHand, Vector3i targetBlock, BlockType blockType,
                                                 float currentDamage, float damage) {
        if (itemInHand != null || targetBlock != null || blockType != null
                || currentDamage != 0f || damage != 0f) {
            return false;
        }
        return false;
    }
}

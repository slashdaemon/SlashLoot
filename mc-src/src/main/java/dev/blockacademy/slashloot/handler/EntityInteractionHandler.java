package dev.blockacademy.slashloot.handler;

import dev.blockacademy.slashloot.compat.Vehicles;
import dev.blockacademy.slashloot.core.Handling;
import dev.blockacademy.slashloot.core.LootContainer;
import dev.blockacademy.slashloot.core.LootRoller;
import dev.blockacademy.slashloot.store.PlayerLootEntry;
import dev.blockacademy.slashloot.store.SlashLootState;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.ContainerEntity;
import net.minecraft.world.level.storage.loot.LootTable;

/**
 * Chest minecart / hopper minecart / chest boat equivalent of {@link ContainerInteractionHandler}.
 * Same decision function, different identity (entity UUID instead of block position).
 *
 * <p>No animation delegation here: {@code AbstractMinecartContainer} has no {@code startOpen}
 * override, so there is nothing to forward to.
 */
public final class EntityInteractionHandler {

    private EntityInteractionHandler() {}

    public static InteractionResult interact(
            ServerPlayer player, ServerLevel level, InteractionHand hand, Entity entity) {

        if (player.isSpectator()) return InteractionResult.PASS;

        // A plain click boards an empty chest boat. Vanilla opens it on a sneak-click, or when the
        // seat is taken.
        if (Vehicles.isChestBoat(entity) && !player.isSecondaryUseActive() && entity.getPassengers().isEmpty()) {
            return InteractionResult.PASS;
        }

        return openInstanced(player, level, entity) ? InteractionResult.SUCCESS : InteractionResult.PASS;
    }

    /**
     * Opens the player's personal copy if this vehicle is ours. Also called for a rider pressing the
     * inventory key, which reaches the vehicle without any click.
     */
    public static boolean openInstanced(ServerPlayer player, ServerLevel level, Entity entity) {
        Handling.Decision decision = Handling.forEntity(level, entity);
        Handling.logEntity(level, entity, decision);
        if (!decision.instanced()) return false;

        ContainerEntity ce = (ContainerEntity) entity;
        SlashLootState store = SlashLootState.get(level);
        PlayerLootEntry entry = store.entityEntry(entity.getUUID());

        LootContainer container = entry.get(player.getUUID());
        if (container == null) {
            ResourceKey<LootTable> table = Vehicles.lootTable(ce);
            container = entry.newContainer(decision.slots());
            if (table != null) {
                LootRoller.rollForEntity(level, entity, table, Vehicles.lootSeed(ce), player, container);
            }
            entry.put(player.getUUID(), container);
            store.setDirty();
        }
        container.trackOrigin(entity);

        player.openMenu(decision.kind().menuProvider(container, null, entity.getDisplayName()));
        return true;
    }
}

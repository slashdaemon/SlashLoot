package dev.blockacademy.slashloot.handler;

import dev.blockacademy.slashloot.core.Handling;
import dev.blockacademy.slashloot.core.LootContainer;
import dev.blockacademy.slashloot.core.LootRoller;
import dev.blockacademy.slashloot.store.PlayerLootEntry;
import dev.blockacademy.slashloot.store.SlashLootState;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.ChestBoat;

/**
 * Band A copy of the container-entity handler. MC 1.20.1 has no shared {@code ContainerEntity}
 * interface, so {@link Handling} branches on {@code AbstractMinecartContainer} vs {@code ChestBoat}
 * and reads the private loot-table fields through {@code @Accessor} mixins.
 *
 * <p>No animation delegation: minecarts and chest boats have no {@code startOpen} override to
 * forward to.
 */
public final class EntityInteractionHandler {

    private EntityInteractionHandler() {}

    /** {@code LoaderBridge.UseEntityHook}: the bridge has already filtered to a server player on a server level. */
    public static InteractionResult interact(ServerPlayer sp, ServerLevel level, InteractionHand hand, Entity entity) {
        if (sp.isSpectator()) return InteractionResult.PASS;

        // A plain click boards an empty chest boat. Vanilla opens it on a sneak-click, or when the
        // seat is taken.
        if (entity instanceof ChestBoat && !sp.isSecondaryUseActive() && entity.getPassengers().isEmpty()) {
            return InteractionResult.PASS;
        }

        return openInstanced(sp, level, entity) ? InteractionResult.SUCCESS : InteractionResult.PASS;
    }

    /**
     * Opens the player's personal copy if this vehicle is ours. Also called for a rider pressing the
     * inventory key, which reaches the vehicle without any click.
     */
    public static boolean openInstanced(ServerPlayer sp, ServerLevel level, Entity entity) {
        Handling.Decision decision = Handling.forEntity(level, entity);
        Handling.logEntity(level, entity, decision);
        if (!decision.instanced()) return false;

        SlashLootState store = SlashLootState.get(level);
        PlayerLootEntry entry = store.entityEntry(entity.getUUID());

        LootContainer container = entry.get(sp.getUUID());
        if (container == null) {
            ResourceLocation table = Handling.lootTableOf(entity);
            container = entry.newContainer(decision.slots());
            if (table != null) {
                LootRoller.rollForEntity(level, entity, table, Handling.lootSeedOf(entity), sp, container);
            }
            entry.put(sp.getUUID(), container);
            store.setDirty();
        }
        container.trackOrigin(entity);

        sp.openMenu(decision.kind().menuProvider(container, null, entity.getDisplayName()));
        return true;
    }
}

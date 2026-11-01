package dev.blockacademy.slashloot.core;

import dev.blockacademy.slashloot.SlashLootCore;
import dev.blockacademy.slashloot.common.SeedDeriver;
import net.minecraft.advancements.CriteriaTriggers;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;

/**
 * Rolls a per-player container from a loot table using a player-derived seed.
 *
 * <p>MC 1.20.1 flavour: loot tables are {@code ResourceLocation} and resolve through
 * {@code MinecraftServer#getLootData()} rather than the later {@code reloadableRegistries()}.
 */
public final class LootRoller {
    private LootRoller() {}

    public static LootContainer rollForBlock(
            ServerLevel level,
            BlockPos pos,
            ResourceLocation tableId,
            long containerSeed,
            ServerPlayer player,
            LootContainer container) {
        return fill(level, Vec3.atCenterOf(pos), tableId, containerSeed, player, container,
                "chest at " + pos);
    }

    public static LootContainer rollForEntity(
            ServerLevel level,
            Entity entity,
            ResourceLocation tableId,
            long containerSeed,
            ServerPlayer player,
            LootContainer container) {
        return fill(level, entity.position(), tableId, containerSeed, player, container,
                "entity " + entity.getUUID());
    }

    private static LootContainer fill(
            ServerLevel level,
            Vec3 origin,
            ResourceLocation tableId,
            long containerSeed,
            ServerPlayer player,
            LootContainer container,
            String what) {

        LootTable table = level.getServer().getLootData().getLootTable(tableId);
        if (table == LootTable.EMPTY) {
            SlashLootCore.LOG.warn("Loot table {} not found for {} - serving empty inventory", tableId, what);
            return container;
        }
        LootParams params = new LootParams.Builder(level)
                .withParameter(LootContextParams.ORIGIN, origin)
                .withLuck(player.getLuck())
                .withParameter(LootContextParams.THIS_ENTITY, player)
                .create(LootContextParamSets.CHEST);
        table.fill(container, params, SeedDeriver.derive(containerSeed, player.getUUID()));
        // Matches vanilla unpackLootTable, which fires this once per player right after the roll.
        CriteriaTriggers.GENERATE_LOOT.trigger(player, tableId);
        return container;
    }
}

package dev.blockacademy.slashloot.core;

import dev.blockacademy.slashloot.SlashLootCore;
import dev.blockacademy.slashloot.common.SeedDeriver;
import dev.blockacademy.slashloot.compat.Advancements;
import dev.blockacademy.slashloot.compat.Ids;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;

/** Rolls a per-player container from a loot table using a player-derived seed. */
public final class LootRoller {

    private LootRoller() {}

    public static LootContainer rollForBlock(
            ServerLevel level,
            BlockPos pos,
            ResourceKey<LootTable> tableKey,
            long containerSeed,
            ServerPlayer player,
            LootContainer container) {
        return fill(level, Vec3.atCenterOf(pos), tableKey, containerSeed, player, container,
                "chest at " + pos);
    }

    public static LootContainer rollForEntity(
            ServerLevel level,
            Entity entity,
            ResourceKey<LootTable> tableKey,
            long containerSeed,
            ServerPlayer player,
            LootContainer container) {
        return fill(level, entity.position(), tableKey, containerSeed, player, container,
                "entity " + entity.getUUID());
    }

    private static LootContainer fill(
            ServerLevel level,
            Vec3 origin,
            ResourceKey<LootTable> tableKey,
            long containerSeed,
            ServerPlayer player,
            LootContainer container,
            String what) {

        LootTable table = level.getServer().reloadableRegistries().getLootTable(tableKey);
        if (table == LootTable.EMPTY) {
            SlashLootCore.LOG.warn("Loot table {} not found for {} - serving empty inventory",
                    Ids.of(tableKey), what);
            return container;
        }
        LootParams params = new LootParams.Builder(level)
                .withParameter(LootContextParams.ORIGIN, origin)
                .withLuck(player.getLuck())
                .withParameter(LootContextParams.THIS_ENTITY, player)
                .create(LootContextParamSets.CHEST);
        table.fill(container, params, SeedDeriver.derive(containerSeed, player.getUUID()));
        // Matches vanilla unpackLootTable, which fires this once per player right after the roll.
        Advancements.generateLoot(player, tableKey);
        return container;
    }
}

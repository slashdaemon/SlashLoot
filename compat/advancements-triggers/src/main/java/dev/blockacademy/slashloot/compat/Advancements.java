package dev.blockacademy.slashloot.compat;

import net.minecraft.advancements.triggers.CriteriaTriggers;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.loot.LootTable;

/**
 * {@code CriteriaTriggers} location seam. MC 26.2+: moved to
 * {@code net.minecraft.advancements.triggers}.
 */
public final class Advancements {
    private Advancements() {}

    /** Fires {@code player_generates_container_loot}, matching vanilla's {@code unpackLootTable}. */
    public static void generateLoot(ServerPlayer player, ResourceKey<LootTable> tableKey) {
        CriteriaTriggers.GENERATE_LOOT.trigger(player, tableKey);
    }
}

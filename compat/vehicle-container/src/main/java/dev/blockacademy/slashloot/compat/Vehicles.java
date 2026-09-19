package dev.blockacademy.slashloot.compat;

import dev.blockacademy.slashloot.core.ContainerKind;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.ContainerEntity;
import net.minecraft.world.entity.vehicle.ChestBoat;
import net.minecraft.world.entity.vehicle.MinecartChest;
import net.minecraft.world.entity.vehicle.MinecartHopper;
import net.minecraft.world.level.storage.loot.LootTable;

/**
 * Container-entity seam. MC 1.21.2 - 1.21.9: getLootTable was renamed to getContainerLootTable; packages unchanged.
 */
public final class Vehicles {
    private Vehicles() {}

    public static ResourceKey<LootTable> lootTable(ContainerEntity ce) {
        return ce.getContainerLootTable();
    }

    public static long lootSeed(ContainerEntity ce) {
        return ce.getContainerLootTableSeed();
    }

    /** Null for any container entity SlashLoot does not know how to serve. */
    public static ContainerKind kindOf(Entity entity) {
        if (entity instanceof MinecartHopper) return ContainerKind.MINECART_HOPPER;
        if (entity instanceof MinecartChest) return ContainerKind.MINECART_CHEST;
        if (entity instanceof ChestBoat) return ContainerKind.CHEST_BOAT;
        return null;
    }

    public static boolean isChestBoat(Entity entity) {
        return entity instanceof ChestBoat;
    }
}

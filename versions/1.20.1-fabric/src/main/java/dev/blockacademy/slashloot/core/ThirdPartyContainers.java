package dev.blockacademy.slashloot.core;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Set;

/**
 * Curated recognition for modded containers that are not shaped like a vanilla chest/barrel/shulker
 * class-wise, but are functionally one. Matched by block registry id, never by class or shared
 * {@code BlockEntityType} — some mods pack a "real" container and a lookalike that must stay
 * untouched onto the same block entity, and only the registry id tells them apart.
 *
 * <p>Band A standalone copy — see {@code mc-src}'s version of this file for the canonical source.
 */
public final class ThirdPartyContainers {

    private ThirdPartyContainers() {}

    /**
     * Cobblemon's Gilded Chests: 8 block variants share one {@code GildedChestBlockEntity} /
     * {@code BlockEntityType}, but {@code cobblemon:gimmighoul_chest} is a Pokémon mimic ("FAKE"
     * type) whose own code guards {@code startOpen}/item-transfer on {@code type != Type.FAKE} — it
     * is not a real lootable container. SlashLoot's interaction handler hooks Fabric's
     * {@code UseBlockCallback}, which fires before Cobblemon's own block {@code use()}, so recognizing
     * the mimic here would let SlashLoot hijack the click and break Cobblemon's mimic-reveal
     * mechanic entirely. Deliberately excluded — match by id, not by type, so it never included.
     *
     * <p>Cobblemon does not ship for MC 1.20.1, but the check is a harmless no-op there — kept for
     * contract parity with mc-src, per this band's "same contract, mechanical port" policy.
     */
    private static final Set<String> COBBLEMON_GILDED_CHESTS = Set.of(
            "cobblemon:gilded_chest",
            "cobblemon:blue_gilded_chest",
            "cobblemon:black_gilded_chest",
            "cobblemon:yellow_gilded_chest",
            "cobblemon:white_gilded_chest",
            "cobblemon:green_gilded_chest",
            "cobblemon:pink_gilded_chest");

    /** 27-slot, {@code ChestMenu.threeRows()}-shaped — safe to classify as {@link ContainerKind#CHEST}. */
    public static boolean isRecognizedChest(BlockState state) {
        return COBBLEMON_GILDED_CHESTS.contains(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
    }
}

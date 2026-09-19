package dev.blockacademy.slashloot.store;

import com.mojang.serialization.Codec;
import dev.blockacademy.slashloot.SlashLootCore;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * The one line that differs between MC 1.21.6 - 1.21.11 and MC 26.1+: from 26.1 the
 * {@code SavedDataType} identity is an {@code Identifier}.
 */
final class StateType {
    private StateType() {}

    static SavedDataType<SlashLootState> create(Codec<SlashLootState> codec) {
        return named(SlashLootCore.MOD_ID, codec);
    }

    /** The pre-rename identity, read once to migrate an existing save. */
    static SavedDataType<SlashLootState> legacy(Codec<SlashLootState> codec) {
        return named(SlashLootCore.LEGACY_ID, codec);
    }

    private static SavedDataType<SlashLootState> named(String id, Codec<SlashLootState> codec) {
        return new SavedDataType<>(Identifier.fromNamespaceAndPath(id, id), SlashLootState::new, codec, null);
    }
}

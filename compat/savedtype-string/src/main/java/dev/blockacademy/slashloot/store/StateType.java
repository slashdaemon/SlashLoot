package dev.blockacademy.slashloot.store;

import com.mojang.serialization.Codec;
import dev.blockacademy.slashloot.SlashLootCore;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * The one line that differs between MC 1.21.6 - 1.21.11 and MC 26.1+: on this generation the
 * {@code SavedDataType} identity is still a plain String.
 */
final class StateType {
    private StateType() {}

    static SavedDataType<SlashLootState> create(Codec<SlashLootState> codec) {
        return new SavedDataType<>(SlashLootCore.MOD_ID, SlashLootState::new, codec, null);
    }

    /** The pre-rename identity, read once to migrate an existing save. */
    static SavedDataType<SlashLootState> legacy(Codec<SlashLootState> codec) {
        return new SavedDataType<>(SlashLootCore.LEGACY_ID, SlashLootState::new, codec, null);
    }
}

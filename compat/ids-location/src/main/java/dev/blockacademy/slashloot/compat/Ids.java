package dev.blockacademy.slashloot.compat;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * Identifier accessors for MC 1.20.5 - 1.21.9, where the type is {@code ResourceLocation} and the
 * accessor is {@code ResourceKey#location()}.
 *
 * <p>Everything shared returns plain namespaced strings, so no shared class ever has to name
 * {@code ResourceLocation} / {@code Identifier} — the pair that swapped identity at MC 1.21.11.
 */
public final class Ids {
    private Ids() {}

    public static String of(ResourceKey<?> key) {
        return key.location().toString();
    }

    public static String dimension(Level level) {
        return level.dimension().location().toString();
    }
}

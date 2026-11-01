package dev.blockacademy.slashloot.compat;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * Identifier accessors for MC 1.21.11 and up, where {@code ResourceLocation} was renamed in place to
 * {@code Identifier} and {@code ResourceKey#location()} became {@code ResourceKey#identifier()}.
 */
public final class Ids {
    private Ids() {}

    public static String of(ResourceKey<?> key) {
        return key.identifier().toString();
    }

    public static String dimension(Level level) {
        return level.dimension().identifier().toString();
    }
}

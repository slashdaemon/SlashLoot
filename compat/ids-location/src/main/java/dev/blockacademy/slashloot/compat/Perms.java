package dev.blockacademy.slashloot.compat;

import net.minecraft.commands.CommandSourceStack;

import java.util.function.Predicate;

/** Permission predicate for MC 1.20.5 - 1.21.9 (integer permission levels). */
public final class Perms {
    private Perms() {}

    public static Predicate<CommandSourceStack> gamemasters() {
        return src -> src.hasPermission(2);
    }
}

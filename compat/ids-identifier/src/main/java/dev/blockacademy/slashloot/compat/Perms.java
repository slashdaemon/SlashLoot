package dev.blockacademy.slashloot.compat;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

import java.util.function.Predicate;

/** Permission predicate for MC 1.21.11 and up, which replaced integer levels with named tiers. */
public final class Perms {
    private Perms() {}

    public static Predicate<CommandSourceStack> gamemasters() {
        return Commands.hasPermission(Commands.LEVEL_GAMEMASTERS);
    }
}

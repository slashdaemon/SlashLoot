package dev.blockacademy.slashloot.loader;

import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.BlockHitResult;

import com.mojang.brigadier.CommandDispatcher;

import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * The one seam between loader-agnostic mod logic and the loader (Band A: Fabric and Forge 1.20.1).
 *
 * <p>Band A copy of {@code mc-src/.../loader/LoaderBridge.java}. Keep the two identical: Band A's
 * {@code loader/fabric} bridge is a copy of the shared {@code loader-fabric} one.
 *
 * <p>Everything below {@code dev.blockacademy.slashloot} outside this package is written against
 * vanilla Minecraft types only; no Fabric or NeoForge class is allowed to cross this interface.
 * The loader entrypoint constructs its own implementation and hands it to
 * {@link dev.blockacademy.slashloot.SlashLootCore#boot}.
 *
 * <p>Deliberately NOT a {@link java.util.ServiceLoader} SPI: there is exactly one implementation per
 * loader and explicit injection avoids the Knot-classloader pitfalls that a service lookup brings.
 */
public interface LoaderBridge {

    /** Directory holding {@code slashloot.json}. Fabric: config dir. Forge / NeoForge: FMLPaths.CONFIGDIR. */
    Path configDir();

    /** Player right-clicked a block. Return anything other than PASS to consume the interaction. */
    void onUseBlock(UseBlockHook hook);

    /** Player right-clicked an entity. Return anything other than PASS to consume the interaction. */
    void onUseEntity(UseEntityHook hook);

    /** Brigadier command registration. */
    void onRegisterCommands(CommandHook hook);

    /** A player finished breaking a block. Fires after the block is gone. */
    void onPlayerBreakBlock(BlockBreakHook hook);

    /** End of each server tick. */
    void onServerTick(Consumer<MinecraftServer> hook);

    @FunctionalInterface
    interface UseBlockHook {
        InteractionResult interact(ServerPlayer player, ServerLevel level, InteractionHand hand, BlockHitResult hit);
    }

    @FunctionalInterface
    interface UseEntityHook {
        InteractionResult interact(ServerPlayer player, ServerLevel level, InteractionHand hand, Entity target);
    }

    @FunctionalInterface
    interface CommandHook {
        void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext ctx);
    }

    @FunctionalInterface
    interface BlockBreakHook {
        void broke(ServerLevel level, BlockPos pos);
    }
}

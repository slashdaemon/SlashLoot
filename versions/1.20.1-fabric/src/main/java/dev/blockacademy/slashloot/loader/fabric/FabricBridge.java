package dev.blockacademy.slashloot.loader.fabric;

import dev.blockacademy.slashloot.loader.LoaderBridge;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;

import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Fabric half of the loader seam. Translates Fabric API events into the loader-neutral hooks the
 * shared tree registers, and does the server-side / player-type filtering once so every hook can
 * assume a {@link ServerPlayer} on a {@link ServerLevel}.
 */
public final class FabricBridge implements LoaderBridge {

    @Override
    public Path configDir() {
        return FabricLoader.getInstance().getConfigDir();
    }

    @Override
    public void onUseBlock(UseBlockHook hook) {
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (world.isClientSide() || !(world instanceof ServerLevel level)) return InteractionResult.PASS;
            if (!(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
            return hook.interact(sp, level, hand, hit);
        });
    }

    @Override
    public void onUseEntity(UseEntityHook hook) {
        UseEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (world.isClientSide() || !(world instanceof ServerLevel level)) return InteractionResult.PASS;
            if (!(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
            return hook.interact(sp, level, hand, entity);
        });
    }

    @Override
    public void onRegisterCommands(CommandHook hook) {
        CommandRegistrationCallback.EVENT.register(
                (dispatcher, registryAccess, environment) -> hook.register(dispatcher, registryAccess));
    }

    @Override
    public void onPlayerBreakBlock(BlockBreakHook hook) {
        PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity) -> {
            if (world instanceof ServerLevel level) hook.broke(level, pos);
        });
    }

    @Override
    public void onServerTick(Consumer<MinecraftServer> hook) {
        ServerTickEvents.END_SERVER_TICK.register(hook::accept);
    }
}

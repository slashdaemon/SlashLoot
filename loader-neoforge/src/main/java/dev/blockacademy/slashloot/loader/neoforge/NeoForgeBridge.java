package dev.blockacademy.slashloot.loader.neoforge;

import dev.blockacademy.slashloot.loader.LoaderBridge;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * NeoForge half of the loader seam. Translates NeoForge's game-bus events into the loader-neutral
 * hooks the shared tree registers, and does the server-side / player-type filtering once so every
 * hook can assume a {@link ServerPlayer} on a {@link ServerLevel} — matching what
 * {@code FabricBridge} guarantees.
 *
 * <p>Only this class and {@link SlashLootNeoForge} name a NeoForge type. Everything below
 * {@code SlashLootCore} is written against vanilla Minecraft only, including the mixins, which
 * apply unchanged because NeoForge is Mojang-mapped like our Loom builds.
 */
public final class NeoForgeBridge implements LoaderBridge {

    @Override
    public Path configDir() {
        return FMLPaths.CONFIGDIR.get();
    }

    @Override
    public void onUseBlock(UseBlockHook hook) {
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGH, (PlayerInteractEvent.RightClickBlock event) -> {
            if (!(event.getEntity() instanceof ServerPlayer sp)) return;
            if (!(event.getLevel() instanceof ServerLevel level)) return;
            InteractionResult result = hook.interact(sp, level, event.getHand(), event.getHitVec());
            if (result == InteractionResult.PASS) return;
            // Consume the interaction the way returning non-PASS does on Fabric: vanilla must not
            // also run its own open logic, or the player gets the world container on top of ours.
            event.setCancellationResult(result);
            event.setCanceled(true);
        });
    }

    @Override
    public void onUseEntity(UseEntityHook hook) {
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGH, (PlayerInteractEvent.EntityInteract event) -> {
            if (!(event.getEntity() instanceof ServerPlayer sp)) return;
            if (!(event.getLevel() instanceof ServerLevel level)) return;
            InteractionResult result = hook.interact(sp, level, event.getHand(), event.getTarget());
            if (result == InteractionResult.PASS) return;
            event.setCancellationResult(result);
            event.setCanceled(true);
        });
    }

    @Override
    public void onRegisterCommands(CommandHook hook) {
        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent event) ->
                hook.register(event.getDispatcher(), event.getBuildContext()));
    }

    @Override
    public void onPlayerBreakBlock(BlockBreakHook hook) {
        // The one NeoForge event whose type moved: BlockEvent.BreakEvent up to 1.21.11,
        // event.level.block.BreakBlockEvent from 26.1. Handled by the break-* source variant that
        // the band's build file selects, exactly like the vanilla-side compat axes.
        NeoForgeBreakEvent.register(hook);
    }

    @Override
    public void onServerTick(Consumer<MinecraftServer> hook) {
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post event) -> hook.accept(event.getServer()));
    }
}

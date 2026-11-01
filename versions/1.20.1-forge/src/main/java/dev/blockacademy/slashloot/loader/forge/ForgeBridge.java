package dev.blockacademy.slashloot.loader.forge;

import dev.blockacademy.slashloot.loader.LoaderBridge;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.fml.loading.FMLPaths;

import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Forge 1.20.1 half of Band A's loader seam: the {@code NeoForgeBridge} of the shared tree,
 * translated from {@code net.neoforged} to {@code net.minecraftforge}. Does the server-side /
 * player-type filtering once so every hook can assume a {@link ServerPlayer} on a
 * {@link ServerLevel}, matching what {@code FabricBridge} guarantees.
 *
 * <p>Uses only Forge API present in 47.1.3, so the same jar runs on NeoForge 1.20.1.
 */
public final class ForgeBridge implements LoaderBridge {

    @Override
    public Path configDir() {
        return FMLPaths.CONFIGDIR.get();
    }

    @Override
    public void onUseBlock(UseBlockHook hook) {
        MinecraftForge.EVENT_BUS.addListener(EventPriority.HIGH, (PlayerInteractEvent.RightClickBlock event) -> {
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
        MinecraftForge.EVENT_BUS.addListener(EventPriority.HIGH, (PlayerInteractEvent.EntityInteract event) -> {
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
        MinecraftForge.EVENT_BUS.addListener((RegisterCommandsEvent event) ->
                hook.register(event.getDispatcher(), event.getBuildContext()));
    }

    @Override
    public void onPlayerBreakBlock(BlockBreakHook hook) {
        // BreakEvent fires before the block goes, and is cancellable. LOWEST priority plus the
        // isCanceled check means we only drop stored loot for a break that will really happen.
        MinecraftForge.EVENT_BUS.addListener(EventPriority.LOWEST, (BlockEvent.BreakEvent event) -> {
            if (event.isCanceled()) return;
            if (!(event.getLevel() instanceof ServerLevel level)) return;
            hook.broke(level, event.getPos());
        });
    }

    @Override
    public void onServerTick(Consumer<MinecraftServer> hook) {
        // 1.20.1's TickEvent has no Pre/Post subclasses: it fires twice per tick, once per phase.
        MinecraftForge.EVENT_BUS.addListener((TickEvent.ServerTickEvent event) -> {
            if (event.phase == TickEvent.Phase.END) hook.accept(event.getServer());
        });
    }
}

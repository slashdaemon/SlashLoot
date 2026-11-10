package dev.blockacademy.slashloot.loader.neoforge;

import dev.blockacademy.slashloot.loader.LoaderBridge;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.BlockEvent;

/**
 * NeoForge up to MC 1.21.11: the player break event is {@code BlockEvent.BreakEvent}.
 * MC 26.1 moved it to {@code event.level.block.BreakBlockEvent} — see the sibling variant.
 *
 * <p>Registered at LOWEST priority and skipped when cancelled: unlike Fabric's AFTER event, this
 * one fires BEFORE the break and another mod may still veto it. {@code CleanupHandler} does not
 * trust it either way and re-checks the position on the next tick.
 */
final class NeoForgeBreakEvent {
    private NeoForgeBreakEvent() {}

    /**
     * The cancelling half: runs at NORMAL priority, ahead of the LOWEST-priority cleanup listener
     * below, so a break SlashLoot vetoes is already cancelled by the time cleanup would see it.
     */
    static void registerAttempt(LoaderBridge.BreakAttemptHook hook) {
        NeoForge.EVENT_BUS.addListener((BlockEvent.BreakEvent event) -> {
            if (event.isCanceled()) return;
            if (!(event.getLevel() instanceof ServerLevel level)) return;
            if (!(event.getPlayer() instanceof ServerPlayer sp)) return;
            if (!hook.allow(sp, level, event.getPos(), level.getBlockEntity(event.getPos()))) {
                event.setCanceled(true);
            }
        });
    }

    static void register(LoaderBridge.BlockBreakHook hook) {
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, (BlockEvent.BreakEvent event) -> {
            if (event.isCanceled()) return;
            if (!(event.getLevel() instanceof ServerLevel level)) return;
            hook.broke(level, event.getPos());
        });
    }
}

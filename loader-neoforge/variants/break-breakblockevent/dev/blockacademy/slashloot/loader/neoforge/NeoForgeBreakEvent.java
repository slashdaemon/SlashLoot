package dev.blockacademy.slashloot.loader.neoforge;

import dev.blockacademy.slashloot.loader.LoaderBridge;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.block.BreakBlockEvent;

/**
 * NeoForge on MC 26.1 and up: {@code BlockEvent.BreakEvent} was replaced by
 * {@code event.level.block.BreakBlockEvent}. Same shape, same pre-break-and-cancellable semantics.
 */
final class NeoForgeBreakEvent {
    private NeoForgeBreakEvent() {}

    /**
     * The cancelling half: runs at NORMAL priority, ahead of the LOWEST-priority cleanup listener
     * below, so a break SlashLoot vetoes is already cancelled by the time cleanup would see it.
     */
    static void registerAttempt(LoaderBridge.BreakAttemptHook hook) {
        NeoForge.EVENT_BUS.addListener((BreakBlockEvent event) -> {
            if (event.isCanceled()) return;
            if (!(event.getLevel() instanceof ServerLevel level)) return;
            if (!(event.getPlayer() instanceof ServerPlayer sp)) return;
            if (!hook.allow(sp, level, event.getPos(), level.getBlockEntity(event.getPos()))) {
                event.setCanceled(true);
            }
        });
    }

    static void register(LoaderBridge.BlockBreakHook hook) {
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, (BreakBlockEvent event) -> {
            if (event.isCanceled()) return;
            if (!(event.getLevel() instanceof ServerLevel level)) return;
            hook.broke(level, event.getPos());
        });
    }
}

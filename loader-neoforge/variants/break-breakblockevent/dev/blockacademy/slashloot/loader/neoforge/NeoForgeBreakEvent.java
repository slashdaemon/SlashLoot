package dev.blockacademy.slashloot.loader.neoforge;

import dev.blockacademy.slashloot.loader.LoaderBridge;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.block.BreakBlockEvent;

/**
 * NeoForge on MC 26.1 and up: {@code BlockEvent.BreakEvent} was replaced by
 * {@code event.level.block.BreakBlockEvent}. Same shape, same pre-break-and-cancellable semantics.
 */
final class NeoForgeBreakEvent {
    private NeoForgeBreakEvent() {}

    static void register(LoaderBridge.BlockBreakHook hook) {
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, (BreakBlockEvent event) -> {
            if (event.isCanceled()) return;
            if (!(event.getLevel() instanceof ServerLevel level)) return;
            hook.broke(level, event.getPos());
        });
    }
}

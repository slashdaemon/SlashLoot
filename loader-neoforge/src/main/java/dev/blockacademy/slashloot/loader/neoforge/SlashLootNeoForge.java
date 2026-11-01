package dev.blockacademy.slashloot.loader.neoforge;

import dev.blockacademy.slashloot.SlashLootCore;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;

/**
 * NeoForge entrypoint. Builds the bridge, hands it to the shared core, and gets out of the way —
 * the mirror of {@code SlashLootFabric}.
 *
 * <p>Nothing is registered on the mod event bus: SlashLoot adds no blocks, items, or payloads. All
 * of its hooks are game-bus events, wired inside {@link NeoForgeBridge}.
 */
@Mod("slashloot")
public class SlashLootNeoForge {

    public SlashLootNeoForge(IEventBus modEventBus, ModContainer modContainer) {
        SlashLootCore.boot(new NeoForgeBridge());
    }
}

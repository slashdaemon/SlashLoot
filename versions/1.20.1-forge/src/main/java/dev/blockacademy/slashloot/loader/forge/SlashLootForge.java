package dev.blockacademy.slashloot.loader.forge;

import dev.blockacademy.slashloot.SlashLootCore;
import net.minecraftforge.fml.IExtensionPoint;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.NetworkConstants;

/**
 * Forge 1.20.1 entrypoint. Builds the bridge, hands it to Band A's core, and gets out of the way.
 *
 * <p>No-arg constructor on purpose: the {@code FMLJavaModLoadingContext} constructor parameter is
 * Forge 47.3.10+, and NeoForge 1.20.1 (Forge 47.1.3 API) does not have it.
 */
@Mod(SlashLootCore.MOD_ID)
public final class SlashLootForge {

    public SlashLootForge() {
        // Server-side only: clients don't need SlashLoot, so don't show a version mismatch (red X)
        // in their server list when they lack it.
        ModLoadingContext.get().registerExtensionPoint(IExtensionPoint.DisplayTest.class,
                () -> new IExtensionPoint.DisplayTest(() -> NetworkConstants.IGNORESERVERONLY, (remote, isServer) -> true));
        SlashLootCore.boot(new ForgeBridge());
    }
}

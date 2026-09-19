package dev.blockacademy.slashloot.loader.fabric;

import dev.blockacademy.slashloot.SlashLootCore;
import net.fabricmc.api.ModInitializer;

/** Fabric entrypoint. Builds the bridge, hands it to the shared core, and gets out of the way. */
public class SlashLootFabric implements ModInitializer {

    @Override
    public void onInitialize() {
        SlashLootCore.boot(new FabricBridge());
    }
}

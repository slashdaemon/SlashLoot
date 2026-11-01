package dev.blockacademy.slashloot;

import dev.blockacademy.slashloot.command.SlashLootCommand;
import dev.blockacademy.slashloot.config.SlashLootConfig;
import dev.blockacademy.slashloot.handler.CleanupHandler;
import dev.blockacademy.slashloot.handler.ContainerInteractionHandler;
import dev.blockacademy.slashloot.handler.EntityInteractionHandler;
import dev.blockacademy.slashloot.loader.LoaderBridge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loader-agnostic entrypoint. Each loader's entrypoint builds a {@link LoaderBridge} and calls
 * {@link #boot(LoaderBridge)}; everything after that point is identical on Fabric and NeoForge.
 */
public final class SlashLootCore {
    public static final String MOD_ID = "slashloot";
    /**
     * The mod's id before the SlashLoot rename. Used for nothing but carrying an existing server's
     * {@code config/} file and per-dimension save file over to the new names; see
     * {@code SlashLootConfig#load} and {@code SlashLootState#get}.
     */
    public static final String LEGACY_ID = "slashlootr";
    public static final Logger LOG = LoggerFactory.getLogger("SlashLoot");

    private static LoaderBridge bridge;

    private SlashLootCore() {}

    public static void boot(LoaderBridge loaderBridge) {
        bridge = loaderBridge;
        SlashLootConfig.load(loaderBridge.configDir());

        loaderBridge.onUseBlock(ContainerInteractionHandler::interact);
        loaderBridge.onUseEntity(EntityInteractionHandler::interact);
        loaderBridge.onRegisterCommands(SlashLootCommand::register);
        loaderBridge.onPlayerBreakBlock(CleanupHandler::onBlockBroken);
        loaderBridge.onServerTick(CleanupHandler::onServerTick);

        LOG.info("SlashLoot loaded - server-side per-player loot, no client install required");
    }

    public static LoaderBridge bridge() {
        return bridge;
    }
}

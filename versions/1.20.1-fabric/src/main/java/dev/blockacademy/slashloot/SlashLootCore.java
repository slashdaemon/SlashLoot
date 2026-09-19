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
 * Band A's loader-agnostic entrypoint, the 1.20.1 twin of {@code mc-src}'s {@code SlashLootCore}.
 * The Fabric entrypoint ({@code loader/fabric}) and the Forge entrypoint (in
 * {@code versions/1.20.1-forge}) each build a {@link LoaderBridge} and call {@link #boot}; nothing
 * below this class names a loader type.
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

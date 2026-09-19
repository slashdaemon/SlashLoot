package dev.blockacademy.slashloot;

import dev.blockacademy.slashloot.command.SlashLootCommand;
import dev.blockacademy.slashloot.config.SlashLootConfig;
import dev.blockacademy.slashloot.handler.CleanupHandler;
import dev.blockacademy.slashloot.handler.ContainerInteractionHandler;
import dev.blockacademy.slashloot.handler.EntityInteractionHandler;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Band A entrypoint. The other bands go through a LoaderBridge so one entrypoint serves both
 * Fabric and NeoForge; 1.20.1 is Fabric-only and self-contained, so it wires the events directly.
 */
public class SlashLootMod implements ModInitializer {
    public static final String MOD_ID = "slashloot";
    /**
     * The mod's id before the SlashLoot rename. Used for nothing but carrying an existing server's
     * {@code config/} file and per-dimension save file over to the new names; see
     * {@code SlashLootConfig#load} and {@code SlashLootState#get}.
     */
    public static final String LEGACY_ID = "slashlootr";
    public static final Logger LOG = LoggerFactory.getLogger("SlashLoot");

    @Override
    public void onInitialize() {
        SlashLootConfig.load();
        UseBlockCallback.EVENT.register(new ContainerInteractionHandler());
        UseEntityCallback.EVENT.register(new EntityInteractionHandler());
        CommandRegistrationCallback.EVENT.register(SlashLootCommand::register);
        PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity) -> {
            if (world instanceof ServerLevel level) CleanupHandler.onBlockBroken(level, pos);
        });
        ServerTickEvents.END_SERVER_TICK.register(CleanupHandler::onServerTick);
        LOG.info("SlashLoot loaded - server-side per-player loot, no client install required");
    }
}

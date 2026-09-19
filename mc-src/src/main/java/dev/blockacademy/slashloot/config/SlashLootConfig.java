package dev.blockacademy.slashloot.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.blockacademy.slashloot.SlashLootCore;
import dev.blockacademy.slashloot.core.DebugLog;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/**
 * {@code config/slashloot.json}. Loader-agnostic: the directory arrives from
 * {@link dev.blockacademy.slashloot.loader.LoaderBridge#configDir()}.
 *
 * <p>Blocklists are compared as plain namespaced strings so this class never has to name
 * {@code ResourceLocation} / {@code Identifier}, which swapped identity at MC 1.21.11. Callers pass
 * strings produced by {@link dev.blockacademy.slashloot.compat.Ids}.
 *
 * <p>Every field has a safe default and unknown/missing keys are tolerated, so a config written by
 * an older version keeps working after an update.
 */
public final class SlashLootConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static SlashLootConfig INSTANCE = new SlashLootConfig();
    private static Path path;

    /** Master switch. False makes SlashLoot inert — every container behaves like vanilla. */
    public boolean enabled = true;

    /** Dimension ids (e.g. {@code minecraft:the_nether}) served as plain vanilla shared loot. */
    public Set<String> dimensionBlocklist = new HashSet<>();

    /** Loot table ids (e.g. {@code minecraft:chests/end_city_treasure}) served as vanilla loot. */
    public Set<String> lootTableBlocklist = new HashSet<>();

    /**
     * Instance containers we do not specifically recognise but which still implement
     * {@code RandomizableContainer} / {@code ContainerEntity}. Off by default: leaving unknown
     * modded containers to vanilla is the compatible choice.
     */
    public boolean handleUnknownContainers = false;

    /**
     * Forward open/close to the real world container so lids animate, barrels flip their
     * {@code open} state, and trapped chests emit redstone. Vanilla plays the open sound itself
     * when this is on, so {@link #playOpenCloseSounds} only applies when this is off.
     */
    public boolean delegateContainerAnimation = true;

    /** Manual open sound. Only used when {@link #delegateContainerAnimation} is false. */
    public boolean playOpenCloseSounds = true;

    /** Drop a container's stored per-player loot when a player breaks it. */
    public boolean cleanupOnBreak = true;

    /**
     * Ticks between background prune passes over stored entries in loaded chunks, catching
     * containers destroyed by explosions, pistons, or world edits. 0 disables.
     */
    public int pruneIntervalTicks = 6000;

    /** Entries examined per prune pass. Keeps the sweep off the tick budget on large saves. */
    public int pruneBatchSize = 256;

    /** Log every instance-or-vanilla decision, with position, loot table, and reason. */
    public boolean debugLogging = false;

    public static SlashLootConfig get() {
        return INSTANCE;
    }

    public static void load(Path configDir) {
        path = configDir.resolve("slashloot.json");
        adoptLegacyFile(configDir.resolve(SlashLootCore.LEGACY_ID + ".json"));
        reload();
    }

    /** Re-reads the file from disk. Safe to call at runtime; used by {@code /slashloot reload}. */
    public static void reload() {
        if (path == null) return;
        try {
            if (Files.exists(path)) {
                SlashLootConfig loaded = GSON.fromJson(Files.readString(path), SlashLootConfig.class);
                INSTANCE = loaded == null ? new SlashLootConfig() : loaded.withDefaults();
            } else {
                Files.createDirectories(path.getParent());
                Files.writeString(path, GSON.toJson(INSTANCE));
            }
        } catch (IOException | RuntimeException e) {
            SlashLootCore.LOG.warn("Failed to load slashloot.json, using defaults", e);
            INSTANCE = new SlashLootConfig();
        }
        DebugLog.reset();
    }

    /** Moves a config written under the pre-rename name into place, unless a new one already exists. */
    private static void adoptLegacyFile(Path legacy) {
        if (Files.exists(path) || !Files.exists(legacy)) return;
        try {
            Files.move(legacy, path);
            SlashLootCore.LOG.info("Moved config/{} to config/slashloot.json", legacy.getFileName());
        } catch (IOException e) {
            SlashLootCore.LOG.warn("Could not move config/" + legacy.getFileName() + " to slashloot.json", e);
        }
    }

    /** Repairs nulls left by a partial or hand-edited JSON file. */
    private SlashLootConfig withDefaults() {
        if (dimensionBlocklist == null) dimensionBlocklist = new HashSet<>();
        if (lootTableBlocklist == null) lootTableBlocklist = new HashSet<>();
        if (pruneBatchSize <= 0) pruneBatchSize = 256;
        return this;
    }

    public boolean isDimensionBlocked(String dimensionId) {
        return !dimensionBlocklist.isEmpty() && dimensionBlocklist.contains(dimensionId);
    }

    public boolean isLootTableBlocked(String lootTableId) {
        return !lootTableBlocklist.isEmpty() && lootTableBlocklist.contains(lootTableId);
    }
}

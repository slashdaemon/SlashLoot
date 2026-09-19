package dev.blockacademy.slashloot.core;

import dev.blockacademy.slashloot.compat.Ids;
import dev.blockacademy.slashloot.compat.Vehicles;
import dev.blockacademy.slashloot.config.SlashLootConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.Container;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.ContainerEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.storage.loot.LootTable;

import java.util.Locale;

/**
 * THE decision function. Both the loot-cancelling mixins and the interaction handlers call this and
 * nothing else, so it is structurally impossible for them to disagree.
 *
 * <p>That coupling is the fix for two reported bugs:
 *
 * <ul>
 *   <li><b>Blacklist fallback.</b> The mixins used to cancel the vanilla loot roll unconditionally
 *       while the handlers skipped blacklisted dimensions and tables — so a blacklisted container
 *       opened empty. A {@code VANILLA} verdict now means the mixin does not cancel, and vanilla
 *       generates loot exactly as if SlashLoot were not installed.
 *   <li><b>Unknown modded containers.</b> {@code unpackLootTable} is a default method on the
 *       {@link RandomizableContainer} interface, so the mixin fires for <em>every</em> implementor,
 *       including containers we have no idea how to serve. Anything we will not actually instance
 *       now gets a {@code VANILLA} verdict and is left completely alone.
 * </ul>
 *
 * <p>Reached from {@code unpackLootTable}, which hopper polling calls repeatedly — so this stays
 * side-effect free (beyond deduped debug logging) and interns the {@code VANILLA} verdicts.
 */
public final class Handling {

    public enum Verdict { INSTANCE, VANILLA }

    public record Decision(Verdict verdict, String reason, ContainerKind kind, int slots) {
        public boolean instanced() {
            return verdict == Verdict.INSTANCE;
        }
    }

    // Interned VANILLA verdicts — the common outcomes allocate nothing.
    private static final Decision NOT_A_CONTAINER = vanilla("not_a_loot_container");
    private static final Decision NO_LOOT_TABLE = vanilla("no_loot_table");
    private static final Decision CLIENT_LEVEL = vanilla("client_level");
    private static final Decision DIMENSION_BLOCKED = vanilla("dimension_blocklisted");
    private static final Decision TABLE_BLOCKED = vanilla("table_blocklisted");
    private static final Decision DISABLED = vanilla("disabled");

    private Handling() {}

    private static Decision vanilla(String reason) {
        return new Decision(Verdict.VANILLA, reason, null, 0);
    }

    // ---------------------------------------------------------------- blocks

    /**
     * Decides how a block container should be served. Player-independent on purpose: the mixin has
     * no player to hand us, and the player-dependent gates vanilla applies (chest blocked from
     * above, shulker cannot open) stop vanilla opening the menu too, so they cannot strand us with
     * an empty container.
     */
    public static Decision forBlock(Level level, BlockPos pos, BlockEntity be) {
        if (level == null || level.isClientSide()) return CLIENT_LEVEL;
        if (!(be instanceof RandomizableContainer rc)) return NOT_A_CONTAINER;

        ResourceKey<LootTable> table = rc.getLootTable();
        if (table == null) return NO_LOOT_TABLE;

        SlashLootConfig config = SlashLootConfig.get();
        if (!config.enabled) return DISABLED;
        if (config.isDimensionBlocked(Ids.dimension(level))) return DIMENSION_BLOCKED;
        if (config.isLootTableBlocked(Ids.of(table))) return TABLE_BLOCKED;

        BlockState state = level.getBlockState(pos);
        ContainerKind kind = classifyBlock(be, state);
        if (kind == null) {
            if (!config.handleUnknownContainers) {
                return vanilla("unsupported_container:" + blockId(state));
            }
            kind = ContainerKind.CHEST;
        }

        int slots = kind == ContainerKind.DOUBLE_CHEST
                ? doubleChestSlots(level, pos, state, be)
                : containerSize(be, kind);
        return new Decision(Verdict.INSTANCE, "instanced", kind, slots);
    }

    /** Convenience for the mixins, which only need the yes/no. */
    public static boolean instancesBlock(Level level, BlockPos pos, BlockEntity be) {
        Decision d = forBlock(level, pos, be);
        logBlock(level, pos, be, d);
        return d.instanced();
    }

    private static ContainerKind classifyBlock(BlockEntity be, BlockState state) {
        if (be instanceof ShulkerBoxBlockEntity) return ContainerKind.SHULKER;
        if (be instanceof BarrelBlockEntity) return ContainerKind.BARREL;
        if (be instanceof ChestBlockEntity) {
            if (state.getBlock() instanceof ChestBlock && state.hasProperty(ChestBlock.TYPE)) {
                ChestType type = state.getValue(ChestBlock.TYPE);
                if (type != ChestType.SINGLE) return ContainerKind.DOUBLE_CHEST;
            }
            return ContainerKind.CHEST;
        }
        if (ThirdPartyContainers.isRecognizedChest(state)) return ContainerKind.CHEST;
        return null;
    }

    /**
     * Real slot count off the world container rather than a hardcoded 27, so a modded chest subclass
     * with a different inventory size gets a personal copy of the right shape.
     *
     * <p>Safe to call: {@code getContainerSize} is one of the few container methods that does NOT
     * route through {@code unpackLootTable}.
     */
    private static int containerSize(BlockEntity be, ContainerKind kind) {
        if (be instanceof Container c) {
            int size = c.getContainerSize();
            if (size > 0) return size;
        }
        return kind.defaultSlots;
    }

    private static int doubleChestSlots(Level level, BlockPos pos, BlockState state, BlockEntity be) {
        int here = containerSize(be, ContainerKind.CHEST);
        BlockPos other = pos.relative(ChestBlock.getConnectedDirection(state));
        // Never force-load the neighbour. This runs from unpackLootTable, which a hopper can reach
        // every few ticks; touching an unloaded chunk there would drag chunk loading onto that path.
        if (!level.isLoaded(other)) return here * 2;
        BlockEntity otherBe = level.getBlockEntity(other);
        int there = otherBe instanceof Container ? containerSize(otherBe, ContainerKind.CHEST) : here;
        return here + there;
    }

    private static String blockId(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }

    // -------------------------------------------------------------- entities

    public static Decision forEntity(Level level, Entity entity) {
        if (level == null || level.isClientSide()) return CLIENT_LEVEL;
        if (!(entity instanceof ContainerEntity ce)) return NOT_A_CONTAINER;

        ResourceKey<LootTable> table = Vehicles.lootTable(ce);
        if (table == null) return NO_LOOT_TABLE;

        SlashLootConfig config = SlashLootConfig.get();
        if (!config.enabled) return DISABLED;
        if (config.isDimensionBlocked(Ids.dimension(level))) return DIMENSION_BLOCKED;
        if (config.isLootTableBlocked(Ids.of(table))) return TABLE_BLOCKED;

        ContainerKind kind = Vehicles.kindOf(entity);
        if (kind == null) {
            if (!config.handleUnknownContainers) {
                return vanilla("unsupported_container:" + entityId(entity));
            }
            kind = ContainerKind.MINECART_CHEST;
        }

        int slots = entity instanceof Container c && c.getContainerSize() > 0
                ? c.getContainerSize()
                : kind.defaultSlots;
        return new Decision(Verdict.INSTANCE, "instanced", kind, slots);
    }

    public static boolean instancesEntity(Level level, Entity entity) {
        Decision d = forEntity(level, entity);
        logEntity(level, entity, d);
        return d.instanced();
    }

    // ------------------------------------------------------- prune presence

    /**
     * Structural-only presence check for {@code CleanupHandler}: is there still a container here that
     * SlashLoot could have instanced, ignoring every config gate ({@code enabled}, blocklists)?
     *
     * <p>Deliberately narrower than {@link #forBlock}. A blocklist edit or a temporary {@code enabled:
     * false} makes {@code forBlock} return {@code VANILLA} for a container that is still sitting there
     * with a player's loot in it — pruning on that verdict would delete live per-player data for no
     * reason but a config toggle. This only says yes once the container itself is actually gone:
     * replaced, broken, or already unpacked (its {@code LootTable} tag cleared).
     */
    public static boolean stillPhysicallyPresent(Level level, BlockPos pos, BlockEntity be) {
        return be instanceof RandomizableContainer rc && rc.getLootTable() != null;
    }

    private static String entityId(Entity entity) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
    }

    // --------------------------------------------------------------- logging

    public static void logBlock(Level level, BlockPos pos, BlockEntity be, Decision d) {
        if (!DebugLog.enabled()) return;
        if (d == CLIENT_LEVEL || d == NOT_A_CONTAINER) return;
        String dim = Ids.dimension(level);
        String table = be instanceof RandomizableContainer rc && rc.getLootTable() != null
                ? Ids.of(rc.getLootTable())
                : "none";
        // On a VANILLA verdict there is no kind, so name the block itself — that is the detail
        // someone debugging modpack compatibility actually needs.
        String what = d.kind() != null
                ? d.kind().name().toLowerCase(Locale.ROOT)
                : blockId(level.getBlockState(pos));
        DebugLog.decision(
                dim + "@" + pos.asLong(),
                "[SlashLoot] block " + what + " @ " + dim + " ["
                        + pos.getX() + "," + pos.getY() + "," + pos.getZ() + "] table=" + table
                        + " -> " + d.verdict() + " reason=" + d.reason()
                        + (d.instanced() ? " slots=" + d.slots() : ""));
    }

    public static void logEntity(Level level, Entity entity, Decision d) {
        if (!DebugLog.enabled()) return;
        if (d == CLIENT_LEVEL || d == NOT_A_CONTAINER) return;
        String dim = Ids.dimension(level);
        ResourceKey<LootTable> key = entity instanceof ContainerEntity ce ? Vehicles.lootTable(ce) : null;
        DebugLog.decision(
                "entity@" + entity.getUUID(),
                "[SlashLoot] entity " + (d.kind() != null ? d.kind().name().toLowerCase(Locale.ROOT) + " " : "") + entityId(entity) + " @ " + dim
                        + " uuid=" + entity.getUUID() + " table=" + (key == null ? "none" : Ids.of(key))
                        + " -> " + d.verdict() + " reason=" + d.reason()
                        + (d.instanced() ? " slots=" + d.slots() : ""));
    }
}

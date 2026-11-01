package dev.blockacademy.slashloot.core;

import dev.blockacademy.slashloot.config.SlashLootConfig;
import dev.blockacademy.slashloot.mixin.AccessorAbstractMinecartContainer;
import dev.blockacademy.slashloot.mixin.AccessorChestBoat;
import dev.blockacademy.slashloot.mixin.AccessorRandomizableContainerBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.AbstractMinecartContainer;
import net.minecraft.world.entity.vehicle.ChestBoat;
import net.minecraft.world.entity.vehicle.MinecartChest;
import net.minecraft.world.entity.vehicle.MinecartHopper;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;

import java.util.Locale;

/**
 * Band A's copy of THE decision function. Same contract and same reason strings as the shared
 * {@code mc-src} version — both the loot-cancelling mixins and the interaction handlers call this
 * and nothing else, so they cannot disagree and strand a player with an empty container.
 *
 * <p>Differs from the shared version only in how it reaches a loot table: MC 1.20.1 predates the
 * {@code RandomizableContainer} / {@code ContainerEntity} interfaces and stores loot tables as
 * {@code ResourceLocation} in private fields, so lookups go through the {@code @Accessor} mixins.
 */
public final class Handling {

    public enum Verdict { INSTANCE, VANILLA }

    public record Decision(Verdict verdict, String reason, ContainerKind kind, int slots) {
        public boolean instanced() {
            return verdict == Verdict.INSTANCE;
        }
    }

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

    public static ResourceLocation lootTableOf(BlockEntity be) {
        if (!(be instanceof RandomizableContainerBlockEntity)) return null;
        return ((AccessorRandomizableContainerBlockEntity) (Object) be).slashloot$getLootTable();
    }

    public static long lootSeedOf(BlockEntity be) {
        if (!(be instanceof RandomizableContainerBlockEntity)) return 0L;
        return ((AccessorRandomizableContainerBlockEntity) (Object) be).slashloot$getLootTableSeed();
    }

    public static Decision forBlock(Level level, BlockPos pos, BlockEntity be) {
        if (level == null || level.isClientSide()) return CLIENT_LEVEL;
        if (!(be instanceof RandomizableContainerBlockEntity)) return NOT_A_CONTAINER;

        ResourceLocation table = lootTableOf(be);
        if (table == null) return NO_LOOT_TABLE;

        SlashLootConfig config = SlashLootConfig.get();
        if (!config.enabled) return DISABLED;
        if (config.isDimensionBlocked(dimension(level))) return DIMENSION_BLOCKED;
        if (config.isLootTableBlocked(table.toString())) return TABLE_BLOCKED;

        BlockState state = level.getBlockState(pos);
        ContainerKind kind = classifyBlock(be, state);
        if (kind == null) {
            if (!config.handleUnknownContainers) {
                return vanilla("unsupported_container:" + BuiltInRegistries.BLOCK.getKey(state.getBlock()));
            }
            kind = ContainerKind.CHEST;
        }

        int slots = kind == ContainerKind.DOUBLE_CHEST
                ? doubleChestSlots(level, pos, state, be)
                : containerSize(be, kind);
        return new Decision(Verdict.INSTANCE, "instanced", kind, slots);
    }

    public static boolean instancesBlock(Level level, BlockPos pos, BlockEntity be) {
        Decision d = forBlock(level, pos, be);
        logBlock(level, pos, be, d);
        return d.instanced();
    }

    /**
     * Structural-only presence check for {@code CleanupHandler}: is there still a container here that
     * SlashLoot could have instanced, ignoring every config gate ({@code enabled}, blocklists)?
     *
     * <p>Deliberately narrower than {@link #forBlock}. A blocklist edit or a temporary {@code enabled:
     * false} makes {@code forBlock} return {@code VANILLA} for a container that is still sitting there
     * with a player's loot in it — pruning on that verdict would delete live per-player data for no
     * reason but a config toggle. This only says yes once the container itself is actually gone.
     */
    public static boolean stillPhysicallyPresent(Level level, BlockPos pos, BlockEntity be) {
        return be instanceof RandomizableContainerBlockEntity && lootTableOf(be) != null;
    }

    private static ContainerKind classifyBlock(BlockEntity be, BlockState state) {
        if (be instanceof ShulkerBoxBlockEntity) return ContainerKind.SHULKER;
        if (be instanceof BarrelBlockEntity) return ContainerKind.BARREL;
        if (be instanceof ChestBlockEntity) {
            if (state.getBlock() instanceof ChestBlock && state.hasProperty(ChestBlock.TYPE)) {
                if (state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) return ContainerKind.DOUBLE_CHEST;
            }
            return ContainerKind.CHEST;
        }
        if (ThirdPartyContainers.isRecognizedChest(state)) return ContainerKind.CHEST;
        return null;
    }

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

    // -------------------------------------------------------------- entities

    /** 1.20.1 has no ContainerEntity interface — minecarts and chest boats are unrelated types. */
    public static ResourceLocation lootTableOf(Entity entity) {
        if (entity instanceof AbstractMinecartContainer) {
            return ((AccessorAbstractMinecartContainer) (Object) entity).slashloot$getLootTable();
        }
        if (entity instanceof ChestBoat) {
            return ((AccessorChestBoat) (Object) entity).slashloot$getLootTable();
        }
        return null;
    }

    public static long lootSeedOf(Entity entity) {
        if (entity instanceof AbstractMinecartContainer) {
            return ((AccessorAbstractMinecartContainer) (Object) entity).slashloot$getLootTableSeed();
        }
        if (entity instanceof ChestBoat) {
            return ((AccessorChestBoat) (Object) entity).slashloot$getLootTableSeed();
        }
        return 0L;
    }

    public static Decision forEntity(Level level, Entity entity) {
        if (level == null || level.isClientSide()) return CLIENT_LEVEL;
        if (!(entity instanceof AbstractMinecartContainer) && !(entity instanceof ChestBoat)) {
            return NOT_A_CONTAINER;
        }

        ResourceLocation table = lootTableOf(entity);
        if (table == null) return NO_LOOT_TABLE;

        SlashLootConfig config = SlashLootConfig.get();
        if (!config.enabled) return DISABLED;
        if (config.isDimensionBlocked(dimension(level))) return DIMENSION_BLOCKED;
        if (config.isLootTableBlocked(table.toString())) return TABLE_BLOCKED;

        ContainerKind kind = kindOf(entity);
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

    public static ContainerKind kindOf(Entity entity) {
        if (entity instanceof MinecartHopper) return ContainerKind.MINECART_HOPPER;
        if (entity instanceof MinecartChest) return ContainerKind.MINECART_CHEST;
        if (entity instanceof ChestBoat) return ContainerKind.CHEST_BOAT;
        return null;
    }

    private static String entityId(Entity entity) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
    }

    public static String dimension(Level level) {
        return level.dimension().location().toString();
    }

    // --------------------------------------------------------------- logging

    public static void logBlock(Level level, BlockPos pos, BlockEntity be, Decision d) {
        if (!DebugLog.enabled()) return;
        if (d == CLIENT_LEVEL || d == NOT_A_CONTAINER) return;
        String dim = dimension(level);
        ResourceLocation table = lootTableOf(be);
        // On a VANILLA verdict there is no kind, so name the block itself — that is the detail
        // someone debugging modpack compatibility actually needs.
        String what = d.kind() != null
                ? d.kind().name().toLowerCase(Locale.ROOT)
                : BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).toString();
        DebugLog.decision(
                dim + "@" + pos.asLong(),
                "[SlashLoot] block " + what + " @ " + dim + " ["
                        + pos.getX() + "," + pos.getY() + "," + pos.getZ() + "] table="
                        + (table == null ? "none" : table)
                        + " -> " + d.verdict() + " reason=" + d.reason()
                        + (d.instanced() ? " slots=" + d.slots() : ""));
    }

    public static void logEntity(Level level, Entity entity, Decision d) {
        if (!DebugLog.enabled()) return;
        if (d == CLIENT_LEVEL || d == NOT_A_CONTAINER) return;
        ResourceLocation table = lootTableOf(entity);
        DebugLog.decision(
                "entity@" + entity.getUUID(),
                "[SlashLoot] entity " + (d.kind() != null ? d.kind().name().toLowerCase(Locale.ROOT) + " " : "") + entityId(entity) + " @ " + dimension(level)
                        + " uuid=" + entity.getUUID() + " table=" + (table == null ? "none" : table)
                        + " -> " + d.verdict() + " reason=" + d.reason()
                        + (d.instanced() ? " slots=" + d.slots() : ""));
    }
}

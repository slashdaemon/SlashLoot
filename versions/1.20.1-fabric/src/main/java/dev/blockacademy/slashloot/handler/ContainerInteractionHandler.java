package dev.blockacademy.slashloot.handler;

import dev.blockacademy.slashloot.config.SlashLootConfig;
import dev.blockacademy.slashloot.core.ContainerKind;
import dev.blockacademy.slashloot.core.Handling;
import dev.blockacademy.slashloot.core.LootContainer;
import dev.blockacademy.slashloot.core.LootRoller;
import dev.blockacademy.slashloot.core.OpenSoundFx;
import dev.blockacademy.slashloot.mixin.AccessorShulkerBoxBlock;
import dev.blockacademy.slashloot.store.PlayerLootEntry;
import dev.blockacademy.slashloot.store.SlashLootState;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.CompoundContainer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.monster.piglin.PiglinAi;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Band A copy of the block-container handler. MC 1.20.1 predates the {@code RandomizableContainer}
 * interface, so this targets {@code RandomizableContainerBlockEntity} directly.
 *
 * <p>Whether a container is ours at all is decided exclusively by {@link Handling} — the same call
 * the loot-cancelling mixin makes, so the two can never disagree and strand a player with an empty
 * chest.
 */
public final class ContainerInteractionHandler {

    private ContainerInteractionHandler() {}

    /** {@code LoaderBridge.UseBlockHook}: the bridge has already filtered to a server player on a server level. */
    public static InteractionResult interact(ServerPlayer sp, ServerLevel level, InteractionHand hand, BlockHitResult hit) {
        if (sp.isSpectator()) return InteractionResult.PASS;
        if (sp.isShiftKeyDown() && !sp.getMainHandItem().isEmpty()) return InteractionResult.PASS;

        BlockPos pos = hit.getBlockPos();
        BlockEntity be = level.getBlockEntity(pos);

        Handling.Decision decision = Handling.forBlock(level, pos, be);
        Handling.logBlock(level, pos, be, decision);
        if (!decision.instanced()) return InteractionResult.PASS;

        // Player-dependent gates. Vanilla refuses to open in these cases too, so passing here can
        // never leave a container unrolled.
        //
        // canOpen(Player) is BaseContainerBlockEntity's Lock-NBT check — ChestBlockEntity,
        // BarrelBlockEntity and ShulkerBoxBlockEntity all extend it, so this covers every lockable
        // kind. It is NOT the shulker obstruction check (a different, static method on the Block),
        // which is checked separately below.
        //
        // A double chest is gated on BOTH halves, as vanilla's ChestBlock menu provider does: either
        // half locked or obstructed keeps it shut. Lock checks run first half, then second, so the
        // "locked" message names the same half vanilla's would.
        BlockState state = level.getBlockState(pos);
        DoubleChest pair = decision.kind() == ContainerKind.DOUBLE_CHEST ? DoubleChest.of(level, pos, state, be) : null;
        if (pair != null) {
            if (!canOpen(pair.firstBe(), sp) || !canOpen(pair.secondBe(), sp)) return InteractionResult.PASS;
            if (ChestBlock.isChestBlockedAt(level, pair.first()) || ChestBlock.isChestBlockedAt(level, pair.second())) {
                return InteractionResult.PASS;
            }
        } else {
            if (!canOpen(be, sp)) return InteractionResult.PASS;
            if (be instanceof ChestBlockEntity && ChestBlock.isChestBlockedAt(level, pos)) {
                return InteractionResult.PASS;
            }
        }
        if (be instanceof ShulkerBoxBlockEntity sbe
                && !AccessorShulkerBoxBlock.slashloot$canOpen(state, level, pos, sbe)) {
            return InteractionResult.PASS;
        }

        Built built = buildPerPlayerContainer(level, pos, be, pair, sp, decision);
        if (built == null) return InteractionResult.PASS;

        sp.openMenu(decision.kind().menuProvider(built.container(), built.title()));
        awardVanillaOpenEffects(sp, decision.kind());

        // With delegation on, vanilla's ContainerOpenersCounter already played the open sound.
        SlashLootConfig config = SlashLootConfig.get();
        if (!config.delegateContainerAnimation && config.playOpenCloseSounds) {
            OpenSoundFx.playOpen(level, pos, decision.kind());
        }
        return InteractionResult.SUCCESS;
    }

    /**
     * Vanilla awards these from inside {@code ChestBlock}/{@code BarrelBlock}/{@code ShulkerBoxBlock}
     * {@code use()}, which SlashLoot's handler replaces entirely — so they have to be reproduced here,
     * or a guarded container never angers nearby piglins and the stats never advance.
     */
    private static void awardVanillaOpenEffects(ServerPlayer player, ContainerKind kind) {
        switch (kind) {
            case CHEST, DOUBLE_CHEST -> player.awardStat(Stats.OPEN_CHEST);
            case BARREL -> player.awardStat(Stats.OPEN_BARREL);
            case SHULKER -> player.awardStat(Stats.OPEN_SHULKER_BOX);
            default -> { return; }
        }
        PiglinAi.angerNearbyPiglins(player, true);
    }

    private record Built(Container container, Component title) {}

    private static Built buildPerPlayerContainer(
            ServerLevel level,
            BlockPos pos,
            BlockEntity be,
            DoubleChest pair,
            ServerPlayer sp,
            Handling.Decision decision) {

        SlashLootState store = SlashLootState.get(level);
        boolean delegate = SlashLootConfig.get().delegateContainerAnimation;

        if (pair == null) {
            // A double chest whose other half is gone or not a chest is served as this half alone
            // rather than a broken menu.
            int slots = decision.kind() == ContainerKind.DOUBLE_CHEST ? Math.max(1, decision.slots() / 2) : decision.slots();
            LootContainer c = getOrRoll(store, level, pos, be, sp, slots, delegate);
            return new Built(c, titleOf(be, decision.kind()));
        }

        LootContainer firstC = getOrRoll(store, level, pair.first(), pair.firstBe(), sp,
                pair.firstBe() instanceof Container c ? c.getContainerSize() : 27, delegate);
        LootContainer secondC = getOrRoll(store, level, pair.second(), pair.secondBe(), sp,
                pair.secondBe() instanceof Container c ? c.getContainerSize() : 27, delegate);
        return new Built(new CompoundContainer(firstC, secondC), doubleChestTitle(pair));
    }

    /** {@code BaseContainerBlockEntity#canOpen} is the Lock check; it also shows the "locked" message. */
    private static boolean canOpen(BlockEntity be, ServerPlayer p) {
        return !(be instanceof BaseContainerBlockEntity bcbe) || bcbe.canOpen(p);
    }

    /** Vanilla's double-chest title: first half's custom name, else second half's, else "Large Chest". */
    private static Component doubleChestTitle(DoubleChest pair) {
        if (pair.firstBe() instanceof BaseContainerBlockEntity a && a.hasCustomName()) return a.getDisplayName();
        if (pair.secondBe() instanceof BaseContainerBlockEntity b && b.hasCustomName()) return b.getDisplayName();
        return ContainerKind.DOUBLE_CHEST.defaultTitle();
    }

    /**
     * Both halves of a double chest in vanilla's order: the RIGHT half is first ({@code ChestBlock}'s
     * {@code DoubleBlockCombiner} calls it FIRST), so the top rows of the merged menu, the lock-check
     * order and the title all follow the same half vanilla's would. Each half's personal container is
     * keyed by its own position, so the order is display only.
     */
    private record DoubleChest(BlockPos first, BlockEntity firstBe, BlockPos second, BlockEntity secondBe) {

        /** Null when the other half is gone or not a chest; the clicked half is then served alone. */
        static DoubleChest of(ServerLevel level, BlockPos pos, BlockState state, BlockEntity be) {
            BlockPos otherPos = pos.relative(ChestBlock.getConnectedDirection(state));
            BlockEntity otherBe = level.getBlockEntity(otherPos);
            if (!(otherBe instanceof ChestBlockEntity)) return null;
            return state.getValue(ChestBlock.TYPE) == ChestType.RIGHT
                    ? new DoubleChest(pos, be, otherPos, otherBe)
                    : new DoubleChest(otherPos, otherBe, pos, be);
        }
    }

    private static Component titleOf(BlockEntity be, ContainerKind kind) {
        return be instanceof BaseContainerBlockEntity bcbe ? bcbe.getDisplayName() : kind.defaultTitle();
    }

    private static LootContainer getOrRoll(
            SlashLootState store,
            ServerLevel level,
            BlockPos pos,
            BlockEntity be,
            ServerPlayer sp,
            int slots,
            boolean delegate) {

        PlayerLootEntry entry = store.blockEntry(pos.asLong());
        LootContainer existing = entry.get(sp.getUUID());
        if (existing == null) {
            ResourceLocation table = Handling.lootTableOf(be);
            existing = entry.newContainer(slots);
            if (table != null) {
                LootRoller.rollForBlock(level, pos, table, Handling.lootSeedOf(be), sp, existing);
            }
            entry.put(sp.getUUID(), existing);
            store.setDirty();
        }
        // Point the animation at the real world container for this session only.
        existing.delegateTo(delegate && be instanceof Container c ? c : null);
        // Distance/validity tracking always applies, independent of the animation-delegation setting.
        existing.trackOrigin(level, pos);
        return existing;
    }
}

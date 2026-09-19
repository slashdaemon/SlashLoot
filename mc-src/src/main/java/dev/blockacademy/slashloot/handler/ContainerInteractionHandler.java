package dev.blockacademy.slashloot.handler;

import dev.blockacademy.slashloot.compat.PiglinAnger;
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
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.CompoundContainer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Right-click on a chest / barrel / shulker box that still carries a LootTable tag: substitute the
 * vanilla menu with a per-player container rolled from that loot table.
 *
 * <p>Whether a container is ours at all is decided exclusively by {@link Handling} — the same call
 * the loot-cancelling mixin makes, so the two can never disagree and strand a player with an empty
 * chest.
 */
public final class ContainerInteractionHandler {

    private ContainerInteractionHandler() {}

    public static InteractionResult interact(
            ServerPlayer player, ServerLevel level, InteractionHand hand, BlockHitResult hit) {

        if (player.isSpectator()) return InteractionResult.PASS;
        // Sneak-with-item is a block placement, not an open.
        if (player.isShiftKeyDown() && !player.getMainHandItem().isEmpty()) return InteractionResult.PASS;

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
        if (be instanceof BaseContainerBlockEntity bcbe && !bcbe.canOpen(player)) return InteractionResult.PASS;
        if (be instanceof ChestBlockEntity && ChestBlock.isChestBlockedAt(level, pos)) {
            return InteractionResult.PASS;
        }
        BlockState state = level.getBlockState(pos);
        if (be instanceof ShulkerBoxBlockEntity sbe
                && !AccessorShulkerBoxBlock.slashloot$canOpen(state, level, pos, sbe)) {
            return InteractionResult.PASS;
        }

        Built built = buildPerPlayerContainer(level, pos, state, be, player, decision);
        if (built == null) return InteractionResult.PASS;

        player.openMenu(decision.kind().menuProvider(built.container(), built.title()));
        awardVanillaOpenEffects(level, player, decision.kind());

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
    private static void awardVanillaOpenEffects(ServerLevel level, ServerPlayer player, ContainerKind kind) {
        switch (kind) {
            case CHEST, DOUBLE_CHEST -> player.awardStat(Stats.OPEN_CHEST);
            case BARREL -> player.awardStat(Stats.OPEN_BARREL);
            case SHULKER -> player.awardStat(Stats.OPEN_SHULKER_BOX);
            default -> { return; }
        }
        PiglinAnger.anger(level, player);
    }

    private record Built(Container container, Component title) {}

    /**
     * Single containers return one personal container. Double chests return a vanilla
     * {@link CompoundContainer} over both halves' personal containers — which also means vanilla
     * forwards {@code startOpen}/{@code stopOpen} to both halves, so both lids animate.
     */
    private static Built buildPerPlayerContainer(
            ServerLevel level,
            BlockPos pos,
            BlockState state,
            BlockEntity be,
            ServerPlayer player,
            Handling.Decision decision) {

        SlashLootState store = SlashLootState.get(level);
        boolean delegate = SlashLootConfig.get().delegateContainerAnimation;

        if (decision.kind() != ContainerKind.DOUBLE_CHEST) {
            LootContainer c = getOrRoll(store, level, pos, be, player, decision.slots(), delegate);
            return new Built(c, titleOf(be, decision.kind()));
        }

        Direction connected = ChestBlock.getConnectedDirection(state);
        BlockPos otherPos = pos.relative(connected);
        BlockEntity otherBe = level.getBlockEntity(otherPos);
        if (!(otherBe instanceof ChestBlockEntity) || !(otherBe instanceof RandomizableContainer)) {
            // Other half gone or not a chest — serve this half alone rather than a broken menu.
            int half = Math.max(1, decision.slots() / 2);
            LootContainer c = getOrRoll(store, level, pos, be, player, half, delegate);
            return new Built(c, titleOf(be, decision.kind()));
        }

        // Canonical ordering so the two halves never swap between openings.
        BlockPos first = pos.asLong() < otherPos.asLong() ? pos : otherPos;
        BlockPos second = first == pos ? otherPos : pos;
        BlockEntity firstBe = first == pos ? be : otherBe;
        BlockEntity secondBe = first == pos ? otherBe : be;

        int firstSlots = firstBe instanceof Container c ? c.getContainerSize() : 27;
        int secondSlots = secondBe instanceof Container c ? c.getContainerSize() : 27;

        LootContainer firstC = getOrRoll(store, level, first, firstBe, player, firstSlots, delegate);
        LootContainer secondC = getOrRoll(store, level, second, secondBe, player, secondSlots, delegate);
        // Matches vanilla: the merged menu takes its title from one canonical half, not both.
        return new Built(new CompoundContainer(firstC, secondC), titleOf(firstBe, decision.kind()));
    }

    private static Component titleOf(BlockEntity be, ContainerKind kind) {
        return be instanceof BaseContainerBlockEntity bcbe ? bcbe.getDisplayName() : kind.defaultTitle();
    }

    private static LootContainer getOrRoll(
            SlashLootState store,
            ServerLevel level,
            BlockPos pos,
            BlockEntity be,
            ServerPlayer player,
            int slots,
            boolean delegate) {

        PlayerLootEntry entry = store.blockEntry(pos.asLong());
        LootContainer existing = entry.get(player.getUUID());
        if (existing == null) {
            ResourceKey<LootTable> table = be instanceof RandomizableContainer rc ? rc.getLootTable() : null;
            long seed = be instanceof RandomizableContainer rc ? rc.getLootTableSeed() : 0L;
            existing = entry.newContainer(slots);
            if (table != null) {
                LootRoller.rollForBlock(level, pos, table, seed, player, existing);
            }
            entry.put(player.getUUID(), existing);
            store.setDirty();
        }
        // Point the animation at the real world container for this session only.
        existing.delegateTo(delegate && be instanceof Container c ? c : null);
        // Distance/validity tracking always applies, independent of the animation-delegation setting.
        existing.trackOrigin(level, pos);
        return existing;
    }
}

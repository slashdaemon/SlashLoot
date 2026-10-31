package dev.blockacademy.slashlootr.handler;

import dev.blockacademy.slashlootr.config.SlashLootrConfig;
import dev.blockacademy.slashlootr.core.ContainerKind;
import dev.blockacademy.slashlootr.core.Handling;
import dev.blockacademy.slashlootr.core.LootContainer;
import dev.blockacademy.slashlootr.core.LootRoller;
import dev.blockacademy.slashlootr.core.OpenSoundFx;
import dev.blockacademy.slashlootr.store.PlayerLootEntry;
import dev.blockacademy.slashlootr.store.SlashLootrState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.CompoundContainer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.level.block.ChestBlock;
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

    /** What a menu shows, and the world container it answers for (null with delegation off). */
    private record Opened(Container personal, Container world) {}

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
        if (be instanceof ShulkerBoxBlockEntity sbe && !sbe.canOpen(player)) return InteractionResult.PASS;
        if (be instanceof ChestBlockEntity && ChestBlock.isChestBlockedAt(level, pos)) {
            return InteractionResult.PASS;
        }

        BlockState state = level.getBlockState(pos);
        Opened opened = buildPerPlayerContainer(level, pos, state, be, player, decision);

        player.openMenu(decision.kind().menuProvider(opened.personal(), opened.world()));

        // With delegation on, vanilla's ContainerOpenersCounter already played the open sound.
        SlashLootrConfig config = SlashLootrConfig.get();
        if (!config.delegateContainerAnimation && config.playOpenCloseSounds) {
            OpenSoundFx.playOpen(level, pos, decision.kind());
        }
        return InteractionResult.SUCCESS;
    }

    /**
     * Single containers return one personal container. Double chests return a vanilla
     * {@link CompoundContainer} over both halves' personal containers — which also means vanilla
     * forwards {@code startOpen}/{@code stopOpen} to both halves, so both lids animate.
     */
    private static Opened buildPerPlayerContainer(
            ServerLevel level,
            BlockPos pos,
            BlockState state,
            BlockEntity be,
            ServerPlayer player,
            Handling.Decision decision) {

        SlashLootrState store = SlashLootrState.get(level);
        boolean delegate = SlashLootrConfig.get().delegateContainerAnimation;

        if (decision.kind() != ContainerKind.DOUBLE_CHEST) {
            return new Opened(
                    getOrRoll(store, level, pos, be, player, decision.slots(), delegate), worldView(be, delegate));
        }

        Direction connected = ChestBlock.getConnectedDirection(state);
        BlockPos otherPos = pos.relative(connected);
        BlockEntity otherBe = level.getBlockEntity(otherPos);
        if (!(otherBe instanceof ChestBlockEntity) || !(otherBe instanceof RandomizableContainer)) {
            // Other half gone or not a chest — serve this half alone rather than a broken menu.
            int half = Math.max(1, decision.slots() / 2);
            return new Opened(getOrRoll(store, level, pos, be, player, half, delegate), worldView(be, delegate));
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
        Container firstWorld = worldView(firstBe, delegate);
        Container secondWorld = worldView(secondBe, delegate);
        return new Opened(
                new CompoundContainer(firstC, secondC),
                firstWorld != null && secondWorld != null ? new CompoundContainer(firstWorld, secondWorld) : null);
    }

    private static Container worldView(BlockEntity be, boolean delegate) {
        return delegate && be instanceof Container c ? c : null;
    }

    private static LootContainer getOrRoll(
            SlashLootrState store,
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
        return existing;
    }
}

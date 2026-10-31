package dev.blockacademy.slashlootr.handler;

import dev.blockacademy.slashlootr.config.SlashLootrConfig;
import dev.blockacademy.slashlootr.core.ContainerKind;
import dev.blockacademy.slashlootr.core.Handling;
import dev.blockacademy.slashlootr.core.LootContainer;
import dev.blockacademy.slashlootr.core.LootRoller;
import dev.blockacademy.slashlootr.core.OpenSoundFx;
import dev.blockacademy.slashlootr.store.PlayerLootEntry;
import dev.blockacademy.slashlootr.store.SlashLootrState;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.CompoundContainer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Band A copy of the block-container handler. MC 1.20.1 predates the {@code RandomizableContainer}
 * interface, so this targets {@code RandomizableContainerBlockEntity} directly.
 *
 * <p>Whether a container is ours at all is decided exclusively by {@link Handling} — the same call
 * the loot-cancelling mixin makes, so the two can never disagree and strand a player with an empty
 * chest.
 */
public class ContainerInteractionHandler implements UseBlockCallback {

    /** What a menu shows, and the world container it answers for (null with delegation off). */
    private record Opened(Container personal, Container world) {}

    @Override
    public InteractionResult interact(Player player, Level world, InteractionHand hand, BlockHitResult hit) {
        if (world.isClientSide()) return InteractionResult.PASS;
        if (!(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
        if (player.isSpectator()) return InteractionResult.PASS;
        if (player.isShiftKeyDown() && !player.getMainHandItem().isEmpty()) return InteractionResult.PASS;

        ServerLevel level = (ServerLevel) world;
        BlockPos pos = hit.getBlockPos();
        BlockEntity be = level.getBlockEntity(pos);

        Handling.Decision decision = Handling.forBlock(level, pos, be);
        Handling.logBlock(level, pos, be, decision);
        if (!decision.instanced()) return InteractionResult.PASS;

        // Player-dependent gates. Vanilla refuses to open in these cases too, so passing here can
        // never leave a container unrolled.
        if (be instanceof ShulkerBoxBlockEntity sbe && !sbe.canOpen(sp)) return InteractionResult.PASS;
        if (be instanceof ChestBlockEntity && ChestBlock.isChestBlockedAt(level, pos)) {
            return InteractionResult.PASS;
        }

        BlockState state = level.getBlockState(pos);
        Opened opened = buildPerPlayerContainer(level, pos, state, be, sp, decision);

        sp.openMenu(decision.kind().menuProvider(opened.personal(), opened.world()));

        // With delegation on, vanilla's ContainerOpenersCounter already played the open sound.
        SlashLootrConfig config = SlashLootrConfig.get();
        if (!config.delegateContainerAnimation && config.playOpenCloseSounds) {
            OpenSoundFx.playOpen(level, pos, decision.kind());
        }
        return InteractionResult.SUCCESS;
    }

    private static Opened buildPerPlayerContainer(
            ServerLevel level,
            BlockPos pos,
            BlockState state,
            BlockEntity be,
            ServerPlayer sp,
            Handling.Decision decision) {

        SlashLootrState store = SlashLootrState.get(level);
        boolean delegate = SlashLootrConfig.get().delegateContainerAnimation;

        if (decision.kind() != ContainerKind.DOUBLE_CHEST) {
            return new Opened(
                    getOrRoll(store, level, pos, be, sp, decision.slots(), delegate), worldView(be, delegate));
        }

        Direction connected = ChestBlock.getConnectedDirection(state);
        BlockPos otherPos = pos.relative(connected);
        BlockEntity otherBe = level.getBlockEntity(otherPos);
        if (!(otherBe instanceof ChestBlockEntity) || !(otherBe instanceof RandomizableContainerBlockEntity)) {
            int half = Math.max(1, decision.slots() / 2);
            return new Opened(getOrRoll(store, level, pos, be, sp, half, delegate), worldView(be, delegate));
        }

        // Canonical ordering so the two halves never swap between openings.
        BlockPos first = pos.asLong() < otherPos.asLong() ? pos : otherPos;
        BlockPos second = first == pos ? otherPos : pos;
        BlockEntity firstBe = first == pos ? be : otherBe;
        BlockEntity secondBe = first == pos ? otherBe : be;

        int firstSlots = firstBe instanceof Container c ? c.getContainerSize() : 27;
        int secondSlots = secondBe instanceof Container c ? c.getContainerSize() : 27;

        LootContainer firstC = getOrRoll(store, level, first, firstBe, sp, firstSlots, delegate);
        LootContainer secondC = getOrRoll(store, level, second, secondBe, sp, secondSlots, delegate);
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
        return existing;
    }
}

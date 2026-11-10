package dev.blockacademy.slashloot.handler;

import dev.blockacademy.slashloot.core.ContainerKind;
import dev.blockacademy.slashloot.core.DebugLog;
import dev.blockacademy.slashloot.core.Handling;
import dev.blockacademy.slashloot.core.LootContainer;
import dev.blockacademy.slashloot.core.LootRoller;
import dev.blockacademy.slashloot.store.LootImport;
import dev.blockacademy.slashloot.store.PlayerLootEntry;
import dev.blockacademy.slashloot.store.SlashLootState;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundLevelEventPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.DecoratedPotBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootTable;

/**
 * Per-player decorated pots. A pot has no menu: vanilla hands its loot out by breaking it, so the
 * first player to break one takes it for everyone. Here each player who hits a pot gets their own
 * roll dropped at it, exactly once, and the pot itself never breaks in survival — the behaviour
 * players expect from per-player loot mods.
 *
 * <p>Every entry point asks {@link Handling} first and acts only on a {@code POT} INSTANCE verdict,
 * so a pot left to vanilla (pots disabled, table or dimension blocklisted) is untouched here too.
 *
 * <p>Storage needs nothing new. A player's pot copy is a 1-slot {@link LootContainer} in the same
 * {@code blocks} map chests use: rolled into, dropped, and left empty. An entry that exists but is
 * empty means "already claimed".
 */
public final class PotHandler {

    /** Vanilla level event: block-destroy particles plus the block's break sound. */
    private static final int LEVEL_EVENT_DESTROY = 2001;

    private PotHandler() {}

    /**
     * Right-click, called from {@link ContainerInteractionHandler} once {@link Handling} has said
     * {@code POT}. An empty hand claims the player's loot. Holding an item only wobbles the pot,
     * which also stops vanilla inserting that item into the shared world pot.
     */
    public static InteractionResult onUse(
            ServerPlayer player, ServerLevel level, BlockPos pos, BlockEntity be, InteractionHand hand) {
        if (!(be instanceof DecoratedPotBlockEntity pot)) return InteractionResult.PASS;
        if (player.getItemInHand(hand).isEmpty()) {
            claim(player, level, pos, pot);
        } else {
            refuse(player, level, pos, pot);
        }
        return InteractionResult.SUCCESS;
    }

    /**
     * A player is about to break a block. Returns false to cancel the break.
     *
     * <p>Survival and adventure: the break is cancelled and the player's loot is dropped instead;
     * the server re-sends the block, undoing the client's instant-break prediction. Creative breaks
     * it normally, so an operator can still clear a pot; the prune then drops its stored entry.
     */
    public static boolean onBreakAttempt(ServerPlayer player, ServerLevel level, BlockPos pos, BlockEntity be) {
        if (!(be instanceof DecoratedPotBlockEntity pot)) return true;
        if (player.isCreative()) return true;
        if (!isOurPot(level, pos, be)) return true;
        claim(player, level, pos, pot);
        return false;
    }

    /**
     * A projectile hit a pot. Returns true when vanilla must not shatter it. A player's projectile
     * claims that player's loot; any other projectile does nothing. Either way the pot survives,
     * because breaking it would take it away from every other player.
     */
    public static boolean onProjectileHit(ServerLevel level, BlockPos pos, ServerPlayer owner) {
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof DecoratedPotBlockEntity pot)) return false;
        if (!isOurPot(level, pos, be)) return false;
        if (owner != null) claim(owner, level, pos, pot);
        return true;
    }

    private static boolean isOurPot(ServerLevel level, BlockPos pos, BlockEntity be) {
        Handling.Decision d = Handling.forBlock(level, pos, be);
        Handling.logBlock(level, pos, be, d);
        return d.instanced() && d.kind() == ContainerKind.POT;
    }

    /** Rolls the player's copy if this is their first claim, drops whatever is in it, and empties it. */
    private static void claim(ServerPlayer player, ServerLevel level, BlockPos pos, DecoratedPotBlockEntity pot) {
        SlashLootState store = SlashLootState.get(level);
        ResourceKey<LootTable> table = pot.getLootTable();
        long seed = pot.getLootTableSeed();

        boolean fresh = !store.hasBlockEntry(pos.asLong());
        PlayerLootEntry entry = store.blockEntry(pos.asLong());
        if (fresh && table != null) {
            LootImport.claimInto(level, LootImport.Category.POT, table, seed, entry, 1);
        }

        LootContainer mine = entry.get(player.getUUID());
        if (mine == null) {
            mine = entry.newContainer(1);
            if (table != null) LootRoller.rollForBlock(level, pos, table, seed, player, mine);
            entry.put(player.getUUID(), mine);
            store.setDirty();
        }

        boolean dropped = false;
        for (int i = 0; i < mine.getContainerSize(); i++) {
            ItemStack stack = mine.removeItemNoUpdate(i);
            if (stack.isEmpty()) continue;
            Containers.dropItemStack(level, pos.getX(), pos.getY(), pos.getZ(), stack);
            dropped = true;
        }

        if (dropped) {
            store.setDirty();
            BlockState state = level.getBlockState(pos);
            // The shatter is shown to this player only: everyone else still sees an intact pot.
            player.connection.send(new ClientboundLevelEventPacket(
                    LEVEL_EVENT_DESTROY, pos, Block.getId(state), false));
            DebugLog.action("[SlashLoot] pot @ " + pos.toShortString() + " dropped loot for " + player.getScoreboardName());
        } else {
            refuse(player, level, pos, pot);
        }
    }

    /** Already claimed, or holding an item: the vanilla "nothing happens" wobble and sound. */
    private static void refuse(ServerPlayer player, ServerLevel level, BlockPos pos, DecoratedPotBlockEntity pot) {
        pot.wobble(DecoratedPotBlockEntity.WobbleStyle.NEGATIVE);
        level.playSound(null, pos, SoundEvents.DECORATED_POT_INSERT_FAIL, SoundSource.BLOCKS, 1.0F, 1.0F);
    }
}

package dev.blockacademy.slashloot.mixin;

import dev.blockacademy.slashloot.core.Handling;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Cancels the vanilla "lazily bake the loot table into the container on first read" pass, but ONLY
 * for containers SlashLoot will actually serve a personal copy of. The chest keeps its LootTable and
 * LootTableSeed NBT tags forever and stays unrolled from the world's perspective; the real loot is
 * rolled per player at menu-open time.
 *
 * <p>{@code unpackLootTable} is a default method on the {@link RandomizableContainer} INTERFACE, so
 * this injection fires for every implementor in the game, ours or not. Cancelling unconditionally
 * (as this did before) left blacklisted and unrecognised containers permanently empty: nobody rolled
 * them. {@link Handling} now owns that yes/no, and the interaction handler asks the same question,
 * so a VANILLA verdict means the container behaves exactly as if SlashLoot were absent — including
 * hopper extraction and comparator output.
 *
 * <p>A null player (hopper polling, comparator reads) is still cancelled when the verdict is
 * INSTANCE. That is deliberate: automated extraction from naturally-generated containers is not
 * possible when every player has their own copy.
 */
@Mixin(RandomizableContainer.class)
public interface MixinRandomizableContainer {

    @Inject(method = "unpackLootTable", at = @At("HEAD"), cancellable = true)
    default void slashloot$cancelVanillaRoll(Player player, CallbackInfo ci) {
        RandomizableContainer self = (RandomizableContainer) this;
        if (!(self instanceof BlockEntity be)) return;
        Level level = be.getLevel();
        if (level == null || level.isClientSide()) return;
        if (Handling.instancesBlock(level, be.getBlockPos(), be)) {
            ci.cancel();
        }
    }
}

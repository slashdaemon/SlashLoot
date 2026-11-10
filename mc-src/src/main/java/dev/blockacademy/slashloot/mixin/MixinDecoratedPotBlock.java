package dev.blockacademy.slashloot.mixin;

import dev.blockacademy.slashloot.handler.PotHandler;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.DecoratedPotBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Vanilla shatters a decorated pot hit by a projectile, which for a per-player pot would take it away
 * from every player at once. For pots SlashLoot instances, a player's projectile claims that
 * player's loot instead, and the pot is never destroyed. {@code PotHandler} asks {@code Handling}
 * first, so pots left to vanilla shatter as usual.
 *
 * <p>{@code onProjectileHit(Level, BlockState, BlockHitResult, Projectile)} has the same signature
 * on every band from 1.20.5 through 26.x. MC 1.20.1 pots carry no loot, so Band A has no copy.
 */
@Mixin(DecoratedPotBlock.class)
public abstract class MixinDecoratedPotBlock {

    @Inject(method = "onProjectileHit", at = @At("HEAD"), cancellable = true)
    private void slashloot$keepPerPlayerPot(
            Level level, BlockState state, BlockHitResult hit, Projectile projectile, CallbackInfo ci) {
        if (!(level instanceof ServerLevel serverLevel)) return;
        ServerPlayer owner = projectile.getOwner() instanceof ServerPlayer sp ? sp : null;
        if (PotHandler.onProjectileHit(serverLevel, hit.getBlockPos(), owner)) {
            ci.cancel();
        }
    }
}

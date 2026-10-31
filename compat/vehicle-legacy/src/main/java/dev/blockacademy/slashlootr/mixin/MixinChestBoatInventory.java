package dev.blockacademy.slashlootr.mixin;

import dev.blockacademy.slashlootr.handler.EntityInteractionHandler;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.ChestBoat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A rider pressing the inventory key opens the boat's chest with no click, so it never reaches
 * {@code EntityInteractionHandler}. Vanilla's roll is cancelled for boats we serve, so without this
 * the rider gets an empty chest.
 */
@Mixin(ChestBoat.class)
public abstract class MixinChestBoatInventory {

    @Inject(method = "openCustomInventoryScreen", at = @At("HEAD"), cancellable = true)
    private void slashlootr$servePersonalCopy(Player player, CallbackInfo ci) {
        if (!(player instanceof ServerPlayer serverPlayer)) return;
        if (!(player.level() instanceof ServerLevel level)) return;
        if (EntityInteractionHandler.openInstanced(serverPlayer, level, (Entity) (Object) this)) {
            ci.cancel();
        }
    }
}

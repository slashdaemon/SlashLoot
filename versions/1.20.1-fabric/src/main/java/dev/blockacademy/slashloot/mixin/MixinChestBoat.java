package dev.blockacademy.slashloot.mixin;

import dev.blockacademy.slashloot.core.Handling;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.ChestBoat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Cancels the vanilla loot roll for chest boats SlashLoot will serve itself. */
@Mixin(ChestBoat.class)
public abstract class MixinChestBoat {

    @Inject(method = "unpackLootTable", at = @At("HEAD"), cancellable = true)
    private void slashloot$cancelVanillaRoll(Player player, CallbackInfo ci) {
        ChestBoat self = (ChestBoat) (Object) this;
        if (self.level().isClientSide()) return;
        if (Handling.instancesEntity(self.level(), self)) {
            ci.cancel();
        }
    }
}

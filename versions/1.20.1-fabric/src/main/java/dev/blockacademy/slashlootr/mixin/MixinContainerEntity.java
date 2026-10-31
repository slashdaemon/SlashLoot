package dev.blockacademy.slashlootr.mixin;

import dev.blockacademy.slashlootr.core.Handling;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.ContainerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Cancels the vanilla loot roll for chest and hopper minecarts SlashLoot will serve itself.
 *
 * <p>Targets the interface, as {@code mc-src} does: in 1.20.1 {@code AbstractMinecartContainer} has
 * no {@code unpackLootTable} of its own, so it inherits this default method.
 */
@Mixin(ContainerEntity.class)
public interface MixinContainerEntity {

    @Inject(method = "unpackChestVehicleLootTable", at = @At("HEAD"), cancellable = true)
    default void slashlootr$cancelVanillaRoll(Player player, CallbackInfo ci) {
        ContainerEntity self = (ContainerEntity) this;
        if (!(self instanceof Entity entity)) return;
        if (entity.level().isClientSide()) return;
        if (Handling.instancesEntity(entity.level(), entity)) {
            ci.cancel();
        }
    }
}

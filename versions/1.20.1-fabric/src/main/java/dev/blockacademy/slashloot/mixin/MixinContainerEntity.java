package dev.blockacademy.slashloot.mixin;

import dev.blockacademy.slashloot.core.Handling;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.ContainerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Cancels the vanilla loot roll for chest minecarts, hopper minecarts and chest boats SlashLoot
 * will serve itself. On 1.20.1 all three roll through {@code ContainerEntity#unpackChestVehicleLootTable}
 * ({@code ChestBoat#unpackLootTable} only delegates to it), so this one hook covers them — the same
 * hook the shared tree uses.
 */
@Mixin(ContainerEntity.class)
public interface MixinContainerEntity {

    @Inject(method = "unpackChestVehicleLootTable", at = @At("HEAD"), cancellable = true)
    default void slashloot$cancelVanillaRoll(Player player, CallbackInfo ci) {
        ContainerEntity self = (ContainerEntity) this;
        if (!(self instanceof Entity entity)) return;
        if (entity.level().isClientSide()) return;
        if (Handling.instancesEntity(entity.level(), entity)) {
            ci.cancel();
        }
    }
}

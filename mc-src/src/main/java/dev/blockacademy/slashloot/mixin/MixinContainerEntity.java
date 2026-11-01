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
 * Entity-container equivalent of {@link MixinRandomizableContainer}: cancels the lazy loot roll for
 * chest minecarts, hopper minecarts, and chest boats — but, as there, only for vehicles SlashLoot
 * will actually serve. Modded {@code ContainerEntity} implementations and blacklisted loot tables
 * fall through to vanilla untouched.
 *
 * <p>Version-neutral: the loot-table accessor moved from {@code getLootTable} to
 * {@code getContainerLootTable} at MC 1.21.2, but that call now lives behind
 * {@code compat/Vehicles}, so this mixin compiles unchanged on every band.
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

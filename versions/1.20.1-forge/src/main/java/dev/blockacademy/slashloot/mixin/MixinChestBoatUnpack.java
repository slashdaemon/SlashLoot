package dev.blockacademy.slashloot.mixin;

import dev.blockacademy.slashloot.core.Handling;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.ChestBoat;
import net.minecraft.world.entity.vehicle.ContainerEntity;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;

/**
 * The chest-boat half of {@link MixinMinecartUnpack}: merges an override of
 * {@code ContainerEntity#unpackChestVehicleLootTable} into {@code ChestBoat}, which does not declare
 * it on 1.20.1 ({@code ChestBoat#unpackLootTable} only delegates to the interface method).
 */
@Mixin(ChestBoat.class)
public abstract class MixinChestBoatUnpack implements ContainerEntity {

    @Override
    public void unpackChestVehicleLootTable(@Nullable Player player) {
        Entity self = (Entity) (Object) this;
        if (!self.level().isClientSide() && Handling.instancesEntity(self.level(), self)) return;
        ContainerEntity.super.unpackChestVehicleLootTable(player);
    }
}

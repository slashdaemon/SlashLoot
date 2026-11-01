package dev.blockacademy.slashloot.mixin;

import dev.blockacademy.slashloot.core.Handling;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.AbstractMinecartContainer;
import net.minecraft.world.entity.vehicle.ContainerEntity;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Forge's replacement for Band A's {@code MixinContainerEntity}, for chest and hopper minecarts.
 *
 * <p>That mixin injects into the {@code ContainerEntity#unpackChestVehicleLootTable} default method,
 * which needs Fabric's Mixin fork: Forge 1.20.1 bundles upstream Mixin 0.8.5, where an injector in an
 * interface is unsupported. Instead this merges an override of that default into
 * {@code AbstractMinecartContainer}, which does not declare it on 1.20.1. Every loot path (menu,
 * hopper pulls, drops on destroy) reaches the roll through that interface call, so the override sees
 * all of them. Because this class implements {@code ContainerEntity}, reobfuscation renames the
 * override to the interface method's SRG name.
 */
@Mixin(AbstractMinecartContainer.class)
public abstract class MixinMinecartUnpack implements ContainerEntity {

    @Override
    public void unpackChestVehicleLootTable(@Nullable Player player) {
        Entity self = (Entity) (Object) this;
        if (!self.level().isClientSide() && Handling.instancesEntity(self.level(), self)) return;
        ContainerEntity.super.unpackChestVehicleLootTable(player);
    }
}

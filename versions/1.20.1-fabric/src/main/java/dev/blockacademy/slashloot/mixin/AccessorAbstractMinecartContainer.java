package dev.blockacademy.slashloot.mixin;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.vehicle.AbstractMinecartContainer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(AbstractMinecartContainer.class)
public interface AccessorAbstractMinecartContainer {
    @Accessor("lootTable") ResourceLocation slashloot$getLootTable();
    @Accessor("lootTableSeed") long slashloot$getLootTableSeed();
}

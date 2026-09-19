package dev.blockacademy.slashloot.mixin;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(RandomizableContainerBlockEntity.class)
public interface AccessorRandomizableContainerBlockEntity {
    @Accessor("lootTable") ResourceLocation slashloot$getLootTable();
    @Accessor("lootTableSeed") long slashloot$getLootTableSeed();
}

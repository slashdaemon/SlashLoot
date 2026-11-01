package dev.blockacademy.slashloot.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * {@code ShulkerBoxBlock.canOpen(...)} — the physical-obstruction check vanilla runs before opening a
 * shulker box (a block placed against its facing side blocks it) — is {@code private static}, so
 * SlashLoot's interaction handler needs an invoker to call it at all.
 */
@Mixin(ShulkerBoxBlock.class)
public interface AccessorShulkerBoxBlock {

    @Invoker("canOpen")
    static boolean slashloot$canOpen(BlockState state, Level level, BlockPos pos, ShulkerBoxBlockEntity be) {
        throw new AssertionError();
    }
}

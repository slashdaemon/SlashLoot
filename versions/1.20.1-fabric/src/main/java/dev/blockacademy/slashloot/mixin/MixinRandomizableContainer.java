package dev.blockacademy.slashloot.mixin;

import dev.blockacademy.slashloot.core.Handling;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Cancels the vanilla lazy-bake of the loot table into the container, but ONLY for containers
 * SlashLoot will actually serve a personal copy of.
 *
 * <p>1.20.1: the RandomizableContainer interface does not exist yet, so this targets the
 * BlockEntity class. Cancelling unconditionally (as this did before) left blacklisted and
 * unrecognised containers permanently empty, because nobody rolled them. {@link Handling} owns that
 * yes/no now, and the interaction handler asks the same question.
 */
@Mixin(RandomizableContainerBlockEntity.class)
public abstract class MixinRandomizableContainer {

    @Inject(method = "unpackLootTable", at = @At("HEAD"), cancellable = true)
    private void slashloot$cancelVanillaRoll(Player player, CallbackInfo ci) {
        RandomizableContainerBlockEntity self = (RandomizableContainerBlockEntity) (Object) this;
        Level level = self.getLevel();
        if (level == null || level.isClientSide()) return;
        if (Handling.instancesBlock(level, self.getBlockPos(), self)) {
            ci.cancel();
        }
    }
}

package dev.blockacademy.slashloot.mixin;

import dev.blockacademy.slashloot.handler.CleanupHandler;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.AbstractMinecartContainer;
import net.minecraft.world.entity.vehicle.ChestBoat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Drops stored per-player loot when a container entity is destroyed for good.
 * {@code RemovalReason#shouldDestroy()} is what separates destruction from a chunk unload, which
 * would otherwise wipe a chest minecart loot copy every time it went out of range.
 */
@Mixin(Entity.class)
public abstract class MixinEntityRemoved {

    @Inject(method = "setRemoved", at = @At("HEAD"))
    private void slashloot$forgetOnDestroy(Entity.RemovalReason reason, CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        if (!(self instanceof AbstractMinecartContainer) && !(self instanceof ChestBoat)) return;
        if (reason == null || !reason.shouldDestroy()) return;
        if (!(self.level() instanceof ServerLevel level)) return;
        CleanupHandler.onEntityDestroyed(level, self.getUUID());
    }
}

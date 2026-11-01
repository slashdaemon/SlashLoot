package dev.blockacademy.slashloot.mixin;

import dev.blockacademy.slashloot.handler.CleanupHandler;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.ContainerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Drops a container entity's stored per-player loot when it is destroyed for good.
 *
 * <p>Deliberately a mixin rather than a loader event: Fabric and NeoForge both only surface entity
 * removal in forms that conflate destruction with chunk unload, which would wipe a chest minecart's
 * loot every time its chunk went out of range. {@code RemovalReason#shouldDestroy()} is the exact
 * distinction we need, and it is identical on both loaders.
 */
@Mixin(Entity.class)
public abstract class MixinEntityRemoved {

    @Inject(method = "setRemoved", at = @At("HEAD"))
    private void slashloot$forgetOnDestroy(Entity.RemovalReason reason, CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        if (!(self instanceof ContainerEntity)) return;
        if (reason == null || !reason.shouldDestroy()) return;
        if (!(self.level() instanceof ServerLevel level)) return;
        CleanupHandler.onEntityDestroyed(level, self.getUUID());
    }
}

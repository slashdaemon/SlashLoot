package dev.blockacademy.slashloot.compat;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.monster.piglin.PiglinAi;

/**
 * {@code PiglinAi.angerNearbyPiglins} seam. MC 1.20.1 - 1.21.1: {@code (Player, boolean)}, no
 * {@code Level} parameter.
 *
 * <p>Lives alongside {@link Vehicles} because this split happens to fall on the exact same version
 * boundary as the vehicle-entity seam above, not because it has anything to do with vehicles — there
 * was no reason to stand up a whole new compat axis for one call site that never diverges from this
 * one independently.
 */
public final class PiglinAnger {
    private PiglinAnger() {}

    public static void anger(ServerLevel level, ServerPlayer player) {
        PiglinAi.angerNearbyPiglins(player, true);
    }
}

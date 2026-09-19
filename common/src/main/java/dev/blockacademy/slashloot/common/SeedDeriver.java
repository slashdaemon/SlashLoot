package dev.blockacademy.slashloot.common;

import java.util.UUID;

/**
 * Per-player loot seed derivation.
 *
 * Same player + same container = same loot (so re-opening shows what they left).
 * Different players = different loot.
 */
public final class SeedDeriver {
    private SeedDeriver() {}

    public static long derive(long containerSeed, UUID player) {
        return containerSeed ^ player.getMostSignificantBits() ^ Long.rotateLeft(player.getLeastSignificantBits(), 17);
    }
}

package dev.blockacademy.slashloot.core;

import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;

/** MC 1.20.5 - 1.21.8: {@code Container} open/close takes a {@code Player}. */
public class LootContainer extends LootContainerBase {

    public LootContainer(int size, Runnable onDirty) {
        super(size, onDirty);
    }

    @Override
    public void startOpen(Player player) {
        Container d = delegate();
        if (d != null) d.startOpen(player);
    }

    @Override
    public void stopOpen(Player player) {
        Container d = takeDelegate();
        if (d != null) d.stopOpen(player);
        clearOrigin();
    }
}

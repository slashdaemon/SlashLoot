package dev.blockacademy.slashloot.core;

import net.minecraft.world.Container;
import net.minecraft.world.entity.ContainerUser;

/** MC 1.21.9+: {@code Container} open/close takes a {@code ContainerUser} instead of a Player. */
public class LootContainer extends LootContainerBase {

    public LootContainer(int size, Runnable onDirty) {
        super(size, onDirty);
    }

    @Override
    public void startOpen(ContainerUser user) {
        Container d = delegate();
        if (d != null) d.startOpen(user);
    }

    @Override
    public void stopOpen(ContainerUser user) {
        Container d = takeDelegate();
        if (d != null) d.stopOpen(user);
        clearOrigin();
    }
}

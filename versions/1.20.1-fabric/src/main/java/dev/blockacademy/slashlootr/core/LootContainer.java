package dev.blockacademy.slashlootr.core;

import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;

/**
 * A player's personal copy of a naturally-generated container's loot.
 *
 * <p>Band A carries this as one class rather than the base/variant split the shared tree uses:
 * MC 1.21.9 changed the open/close parameter from {@code Player} to {@code ContainerUser}, and
 * 1.20.1 only ever sees the {@code Player} form.
 *
 * <ul>
 *   <li><b>Dirty tracking.</b> {@link #setChanged()} marks the owning SavedData dirty.
 *   <li><b>Animation delegation.</b> Open/close forward to the real world container so vanilla's
 *       {@code ContainerOpenersCounter} runs: lids animate, barrels flip their {@code open}
 *       blockstate, and trapped chests emit redstone. Vanilla's {@code CompoundContainer} forwards
 *       to both halves, so double chests work for free.
 * </ul>
 */
public class LootContainer extends SimpleContainer {

    private final Runnable onDirty;
    private Container delegate;

    public LootContainer(int size, Runnable onDirty) {
        super(size);
        this.onDirty = onDirty;
    }

    /** Point this container's open/close animation at the real world container for one session. */
    public void delegateTo(Container worldContainer) {
        this.delegate = worldContainer;
    }

    @Override
    public void setChanged() {
        super.setChanged();
        if (onDirty != null) onDirty.run();
    }

    /** Same range and same-block checks as the world container, while one is open. */
    @Override
    public boolean stillValid(Player player) {
        return delegate == null || delegate.stillValid(player);
    }

    @Override
    public void startOpen(Player player) {
        Container d = this.delegate;
        if (d != null) d.startOpen(player);
    }

    @Override
    public void stopOpen(Player player) {
        Container d = this.delegate;
        this.delegate = null;
        if (d != null) d.stopOpen(player);
    }
}

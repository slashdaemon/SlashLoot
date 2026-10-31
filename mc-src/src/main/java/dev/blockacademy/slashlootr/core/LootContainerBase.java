package dev.blockacademy.slashlootr.core;

import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;

/**
 * A player's personal copy of a naturally-generated container's loot: everything except the
 * open/close overrides, which is all {@code compat/open-*} has to supply.
 *
 * <p>Two jobs beyond plain {@link SimpleContainer}:
 *
 * <ul>
 *   <li><b>Dirty tracking.</b> {@link #setChanged()} marks the owning SavedData dirty. This replaces
 *       the old {@code addListener} approach, which stopped existing in MC 26.1 — overriding
 *       {@code setChanged} works identically on every band we ship.
 *   <li><b>Animation delegation.</b> Subclasses forward open/close to the real world container, so
 *       vanilla's {@code ContainerOpenersCounter} runs: chest and shulker lids animate, barrels flip
 *       their {@code open} blockstate, and trapped chests emit redstone again. Vanilla's
 *       {@code CompoundContainer} forwards both calls to each half, so double chests work for free.
 * </ul>
 *
 * <p>Delegation only stays balanced if vanilla counts the player as an opener, which is what
 * {@link LootChestMenu} and {@link #stillValid} are for. {@code SimpleContainer} is valid forever, so
 * without the override a player could walk out of range with the menu open: vanilla's recheck would
 * drop them from the count, and the eventual close would take it below zero.
 *
 * <p>The delegate is set immediately before the menu opens and cleared on close, so a stale
 * BlockEntity reference is never held across a chunk unload.
 *
 * <p>The subclass exists only because MC 1.21.9 changed the open/close parameter from {@code Player}
 * to {@code ContainerUser}. Nothing else about this class varies by band.
 */
public abstract class LootContainerBase extends SimpleContainer {

    private final Runnable onDirty;
    private Container delegate;

    protected LootContainerBase(int size, Runnable onDirty) {
        super(size);
        this.onDirty = onDirty;
    }

    /** Point this container's open/close animation at the real world container for one session. */
    public void delegateTo(Container worldContainer) {
        this.delegate = worldContainer;
    }

    protected Container delegate() {
        return delegate;
    }

    /** Reads and clears the delegate — used on close so no world reference outlives the session. */
    protected Container takeDelegate() {
        Container d = this.delegate;
        this.delegate = null;
        return d;
    }

    /** Same range and same-block checks as the world container, while one is open. */
    @Override
    public boolean stillValid(Player player) {
        return delegate == null || delegate.stillValid(player);
    }

    @Override
    public void setChanged() {
        super.setChanged();
        if (onDirty != null) onDirty.run();
    }
}

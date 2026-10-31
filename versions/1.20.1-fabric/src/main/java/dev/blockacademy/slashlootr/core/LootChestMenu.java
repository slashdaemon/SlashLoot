package dev.blockacademy.slashlootr.core;

import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;

/**
 * A chest-style menu whose slots read the player's personal container, but which reports the real
 * world container from {@link #getContainer()}.
 *
 * <p>{@code ContainerOpenersCounter} rechecks itself 5 ticks after an open, counting the players
 * whose open {@code ChestMenu} answers {@code getContainer() == this block entity}. A menu reporting
 * the personal container is never counted: the recheck zeroes the count with the GUI still open,
 * firing the close sound and shutting the lid, and the real close then takes the count to -1.
 * Vanilla only fires open and close effects when the count crosses exactly zero, so every later
 * open from there is silent.
 *
 * <p>Vanilla reads {@code getContainer()} only for that ownership check. Slots, quick-move and
 * {@code stillValid} use the container passed to the constructor, and the client never sees this
 * class: it is the same {@link MenuType} on the wire.
 */
public class LootChestMenu extends ChestMenu {

    private final Container worldContainer;

    public LootChestMenu(
            MenuType<?> type, int id, Inventory inventory, Container personal, int rows, Container worldContainer) {
        super(type, id, inventory, personal, rows);
        this.worldContainer = worldContainer;
    }

    @Override
    public Container getContainer() {
        return worldContainer;
    }
}

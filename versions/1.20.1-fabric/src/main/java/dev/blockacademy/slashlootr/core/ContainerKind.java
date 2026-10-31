package dev.blockacademy.slashlootr.core;

import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.HopperMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.ShulkerBoxMenu;

/**
 * Maps a vanilla container type to its display name and menu factory.
 *
 * <p>{@code defaultSlots} is only a fallback: the real slot count is read from the world container
 * at open time ({@code Container#getContainerSize}), so a modded 54-slot chest subclass is served a
 * 54-slot personal copy instead of being silently truncated to 27.
 */
public enum ContainerKind {
    CHEST(27, "container.chest", Style.CHEST),
    BARREL(27, "container.barrel", Style.CHEST),
    SHULKER(27, "container.shulkerBox", Style.SHULKER),
    DOUBLE_CHEST(54, "container.chestDouble", Style.CHEST),
    MINECART_CHEST(27, "entity.minecraft.chest_minecart", Style.CHEST),
    MINECART_HOPPER(5, "entity.minecraft.hopper_minecart", Style.HOPPER),
    CHEST_BOAT(27, "container.chest", Style.CHEST);

    private enum Style { CHEST, SHULKER, HOPPER }

    public final int defaultSlots;
    public final String translationKey;
    private final Style style;

    ContainerKind(int defaultSlots, String translationKey, Style style) {
        this.defaultSlots = defaultSlots;
        this.translationKey = translationKey;
        this.style = style;
    }

    public boolean isBlock() {
        return this == CHEST || this == BARREL || this == SHULKER || this == DOUBLE_CHEST;
    }

    public MenuProvider menuProvider(Container container) {
        return menuProvider(container, null);
    }

    /**
     * @param worldView the real world container behind {@code container}, or null if there is none
     *     to report. See {@link LootChestMenu} for why chest-style menus need it.
     */
    public MenuProvider menuProvider(Container container, Container worldView) {
        return menuProvider(container, worldView, Component.translatable(translationKey));
    }

    /** With an explicit title: vehicles use their entity name, as vanilla does. */
    public MenuProvider menuProvider(Container container, Container worldView, Component title) {
        int slots = container.getContainerSize();

        // ShulkerBoxMenu hardcodes a 27-slot layout; a modded shulker of another size has to fall
        // through to the chest-style branch below, which sizes itself from the container.
        if (style == Style.SHULKER && slots == 27) {
            return new SimpleMenuProvider((id, inv, p) -> new ShulkerBoxMenu(id, inv, container), title);
        }
        if (style == Style.HOPPER && slots == 5) {
            return new SimpleMenuProvider((id, inv, p) -> new HopperMenu(id, inv, container), title);
        }

        // Chest-style: pick the row count that actually matches the container.
        int rows = slots / 9;
        if (slots % 9 != 0 || rows < 1 || rows > 6) {
            // Not a shape any vanilla chest screen can render. Round down to the nearest usable
            // row count; Handling already logs the oddity when debug logging is on.
            rows = Math.max(1, Math.min(6, rows));
        }
        MenuType<ChestMenu> type = switch (rows) {
            case 1 -> MenuType.GENERIC_9x1;
            case 2 -> MenuType.GENERIC_9x2;
            case 4 -> MenuType.GENERIC_9x4;
            case 5 -> MenuType.GENERIC_9x5;
            case 6 -> MenuType.GENERIC_9x6;
            default -> MenuType.GENERIC_9x3;
        };
        int finalRows = rows;
        return new SimpleMenuProvider((id, inv, p) -> worldView != null
                ? new LootChestMenu(type, id, inv, container, finalRows, worldView)
                : new ChestMenu(type, id, inv, container, finalRows), title);
    }
}

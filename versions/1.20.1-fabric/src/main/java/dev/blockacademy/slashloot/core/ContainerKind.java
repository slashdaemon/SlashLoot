package dev.blockacademy.slashloot.core;

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
    MINECART_CHEST(27, "container.minecart", Style.CHEST),
    MINECART_HOPPER(5, "container.hopper", Style.HOPPER),
    CHEST_BOAT(27, "container.chestBoat", Style.CHEST);

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

    /** Generic vanilla title, for callers with no real container name to preserve (e.g. minecart hopper). */
    public Component defaultTitle() {
        return Component.translatable(translationKey);
    }

    /** @param title the real container's display name (custom name if renamed, default name otherwise) */
    public MenuProvider menuProvider(Container container, Component title) {
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
        return new SimpleMenuProvider((id, inv, p) -> new ChestMenu(type, id, inv, container, finalRows), title);
    }
}

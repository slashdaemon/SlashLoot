package dev.blockacademy.slashloot.core;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.Entity;
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

    /** Vanilla's own chest/barrel/shulker reach distance ({@code Container.stillValidBlockEntity}). */
    private static final double MAX_DISTANCE_SQ = 8.0 * 8.0;

    private final Runnable onDirty;
    private Container delegate;
    private ServerLevel originLevel;
    private BlockPos originPos;
    private Entity originEntity;

    public LootContainer(int size, Runnable onDirty) {
        super(size);
        this.onDirty = onDirty;
    }

    /** Point this container's open/close animation at the real world container for one session. */
    public void delegateTo(Container worldContainer) {
        this.delegate = worldContainer;
    }

    /** Ties {@link #stillValid} to a real block position for one session. */
    public void trackOrigin(ServerLevel level, BlockPos pos) {
        this.originLevel = level;
        this.originPos = pos;
        this.originEntity = null;
    }

    /** Ties {@link #stillValid} to a real entity for one session. */
    public void trackOrigin(Entity entity) {
        this.originEntity = entity;
        this.originLevel = null;
        this.originPos = null;
    }

    @Override
    public boolean stillValid(Player player) {
        if (originPos != null && originLevel != null) {
            if (!originLevel.isLoaded(originPos)) return false;
            if (!Handling.stillPhysicallyPresent(originLevel, originPos, originLevel.getBlockEntity(originPos))) {
                return false;
            }
            return player.distanceToSqr(
                    originPos.getX() + 0.5, originPos.getY() + 0.5, originPos.getZ() + 0.5) <= MAX_DISTANCE_SQ;
        }
        if (originEntity != null) {
            return originEntity.isAlive() && player.distanceToSqr(originEntity) <= MAX_DISTANCE_SQ;
        }
        // No origin tracked yet (shouldn't happen once the menu is actually open) — don't force-close.
        return true;
    }

    @Override
    public void setChanged() {
        super.setChanged();
        if (onDirty != null) onDirty.run();
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
        this.originLevel = null;
        this.originPos = null;
        this.originEntity = null;
    }
}

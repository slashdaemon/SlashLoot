package dev.blockacademy.slashloot.core;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.Entity;
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
 * <p>The delegate is set immediately before the menu opens and cleared on close, so a stale
 * BlockEntity reference is never held across a chunk unload.
 *
 * <p><b>Distance/validity.</b> {@code stillValid(Player)} is one of the few {@link Container} methods
 * whose signature never changed to {@code ContainerUser} (checked against every band's mappings), so
 * it lives here rather than in a {@code compat/open-*} variant. Plain {@link SimpleContainer} has no
 * notion of "still valid" — it defaults to always {@code true} — so without this override a personal
 * container's menu would never auto-close for distance or for the backing container being destroyed
 * mid-session, unlike every vanilla container menu.
 *
 * <p>The subclass exists only because MC 1.21.9 changed the open/close parameter from {@code Player}
 * to {@code ContainerUser}. Nothing else about this class varies by band.
 */
public abstract class LootContainerBase extends SimpleContainer {

    /** Vanilla's own chest/barrel/shulker reach distance ({@code Container.stillValidBlockEntity}). */
    private static final double MAX_DISTANCE_SQ = 8.0 * 8.0;

    private final Runnable onDirty;
    private Container delegate;
    private ServerLevel originLevel;
    private BlockPos originPos;
    private Entity originEntity;

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

    /** Clears the tracked origin — used on close so no world/entity reference outlives the session. */
    protected void clearOrigin() {
        this.originLevel = null;
        this.originPos = null;
        this.originEntity = null;
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
}

package dev.blockacademy.slashloot.store;

import dev.blockacademy.slashloot.core.LootContainer;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * One naturally-generated container's per-player loot: player UUID to their personal copy.
 *
 * <p>Shared across every band. Only the surrounding {@code SlashLootState} is band-specific
 * (CompoundTag-based {@code SavedData} up to MC 1.21.4, Codec-based {@code SavedDataType} from
 * 1.21.6), and it serializes through {@link #entries()}.
 *
 * <p>Holds a {@code Runnable} rather than a typed owner so this class never has to name the
 * band-specific state type.
 */
public class PlayerLootEntry {

    private final Runnable onDirty;
    private final Map<UUID, LootContainer> perPlayer = new HashMap<>();

    public PlayerLootEntry(Runnable onDirty) {
        this.onDirty = onDirty;
    }

    /** A fresh, empty personal container wired to this entry's dirty tracking. */
    public LootContainer newContainer(int slots) {
        return new LootContainer(Math.max(1, slots), onDirty);
    }

    public LootContainer get(UUID player) {
        return perPlayer.get(player);
    }

    public LootContainer put(UUID player, LootContainer container) {
        perPlayer.put(player, container);
        return container;
    }

    public boolean removePlayer(UUID player) {
        return perPlayer.remove(player) != null;
    }

    public boolean isEmpty() {
        return perPlayer.isEmpty();
    }

    public int playerCount() {
        return perPlayer.size();
    }

    /** Read-only view for the band-specific serializer. */
    public Map<UUID, LootContainer> entries() {
        return Collections.unmodifiableMap(perPlayer);
    }
}

package dev.blockacademy.slashloot.store;

import dev.blockacademy.slashloot.SlashLootMod;
import dev.blockacademy.slashloot.core.Handling;
import dev.blockacademy.slashloot.core.LootContainer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Per-dimension store of every player's personal copy of every naturally-generated container they
 * have opened. Persisted at {@code world/<dim>/data/slashloot.dat}.
 *
 * <p>MC 1.20.1 flavour: the three-arg {@code computeIfAbsent} and a {@code save(CompoundTag)} with
 * no {@code HolderLookup.Provider}. Same public API and same on-disk shape as every other band, so
 * the shared handlers would work verbatim here and existing saves migrate untouched.
 */
public class SlashLootState extends SavedData {

    private final Map<Long, PlayerLootEntry> blocks = new HashMap<>();
    private final Map<UUID, PlayerLootEntry> entities = new HashMap<>();

    /**
     * The dimension's store, adopting a pre-rename save on first use: when no {@code slashloot} data
     * exists yet but data saved under the old id does, a copy of it is registered under the new id and
     * marked dirty, so the next world save writes it out under the new name. It must be a copy: the
     * storage caches the old object under the old id too, and saving one shared dirty object under two
     * ids writes the old file and clears the flag before the new one is reached. The old file is left
     * on disk untouched, and once the new one exists it is never read again.
     */
    public static SlashLootState get(ServerLevel level) {
        var storage = level.getDataStorage();
        SlashLootState state = storage.get(SlashLootState::load, SlashLootMod.MOD_ID);
        if (state != null) return state;
        SlashLootState legacy = storage.get(SlashLootState::load, SlashLootMod.LEGACY_ID);
        if (legacy == null) {
            return storage.computeIfAbsent(SlashLootState::load, SlashLootState::new, SlashLootMod.MOD_ID);
        }
        SlashLootState copy = load(legacy.save(new CompoundTag()));
        storage.set(SlashLootMod.MOD_ID, copy);
        copy.setDirty();
        SlashLootMod.LOG.info("Migrated {} per-player loot data from the pre-rename save file",
                Handling.dimension(level));
        return copy;
    }

    // ------------------------------------------------------------ accessors

    public PlayerLootEntry blockEntry(long packedPos) {
        return blocks.computeIfAbsent(packedPos, k -> new PlayerLootEntry(this::setDirty));
    }

    public PlayerLootEntry entityEntry(UUID uuid) {
        return entities.computeIfAbsent(uuid, k -> new PlayerLootEntry(this::setDirty));
    }

    public boolean hasBlockEntry(long packedPos) {
        return blocks.containsKey(packedPos);
    }

    public boolean hasEntityEntry(UUID uuid) {
        return entities.containsKey(uuid);
    }

    public long[] blockKeys() {
        long[] out = new long[blocks.size()];
        int i = 0;
        for (Long key : blocks.keySet()) out[i++] = key;
        return out;
    }

    public int blockCount() {
        return blocks.size();
    }

    public int entityCount() {
        return entities.size();
    }

    public int playerCopyCount() {
        int n = 0;
        for (PlayerLootEntry e : blocks.values()) n += e.playerCount();
        for (PlayerLootEntry e : entities.values()) n += e.playerCount();
        return n;
    }

    // -------------------------------------------------------------- removal

    public void forgetBlock(long packedPos) {
        if (blocks.remove(packedPos) != null) setDirty();
    }

    public void forgetEntity(UUID uuid) {
        if (entities.remove(uuid) != null) setDirty();
    }

    public void forgetPlayerEverywhere(UUID player) {
        boolean changed = false;
        for (PlayerLootEntry entry : blocks.values()) changed |= entry.removePlayer(player);
        for (PlayerLootEntry entry : entities.values()) changed |= entry.removePlayer(player);
        if (changed) setDirty();
    }

    /** Drops everything in this dimension. Returns the number of containers cleared. */
    public int forgetAll() {
        int removed = blocks.size() + entities.size();
        blocks.clear();
        entities.clear();
        if (removed > 0) setDirty();
        return removed;
    }

    // -------------------------------------------------------- serialization

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag blockList = new ListTag();
        for (Map.Entry<Long, PlayerLootEntry> e : blocks.entrySet()) {
            CompoundTag entry = new CompoundTag();
            entry.putLong("pos", e.getKey());
            entry.put("players", savePlayers(e.getValue()));
            blockList.add(entry);
        }
        tag.put("blocks", blockList);

        ListTag entityList = new ListTag();
        for (Map.Entry<UUID, PlayerLootEntry> e : entities.entrySet()) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("uuid", e.getKey());
            entry.put("players", savePlayers(e.getValue()));
            entityList.add(entry);
        }
        tag.put("entities", entityList);
        return tag;
    }

    private static ListTag savePlayers(PlayerLootEntry entry) {
        ListTag list = new ListTag();
        for (Map.Entry<UUID, LootContainer> e : entry.entries().entrySet()) {
            CompoundTag row = new CompoundTag();
            row.putUUID("uuid", e.getKey());
            row.putInt("size", e.getValue().getContainerSize());
            row.put("items", e.getValue().createTag());
            list.add(row);
        }
        return list;
    }

    private static void loadPlayers(PlayerLootEntry target, ListTag list) {
        for (int i = 0; i < list.size(); i++) {
            CompoundTag row = list.getCompound(i);
            int size = row.getInt("size");
            LootContainer container = target.newContainer(size > 0 ? size : 27);
            container.fromTag(row.getList("items", Tag.TAG_COMPOUND));
            target.put(row.getUUID("uuid"), container);
        }
    }

    public static SlashLootState load(CompoundTag tag) {
        SlashLootState state = new SlashLootState();
        ListTag blockList = tag.getList("blocks", Tag.TAG_COMPOUND);
        for (int i = 0; i < blockList.size(); i++) {
            CompoundTag entry = blockList.getCompound(i);
            PlayerLootEntry players = new PlayerLootEntry(state::setDirty);
            loadPlayers(players, entry.getList("players", Tag.TAG_COMPOUND));
            state.blocks.put(entry.getLong("pos"), players);
        }
        ListTag entityList = tag.getList("entities", Tag.TAG_COMPOUND);
        for (int i = 0; i < entityList.size(); i++) {
            CompoundTag entry = entityList.getCompound(i);
            PlayerLootEntry players = new PlayerLootEntry(state::setDirty);
            loadPlayers(players, entry.getList("players", Tag.TAG_COMPOUND));
            state.entities.put(entry.getUUID("uuid"), players);
        }
        return state;
    }
}

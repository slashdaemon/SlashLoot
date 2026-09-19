package dev.blockacademy.slashloot.store;

import dev.blockacademy.slashloot.SlashLootCore;
import dev.blockacademy.slashloot.compat.Ids;
import dev.blockacademy.slashloot.core.LootContainer;
import net.minecraft.core.HolderLookup;
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
 * have opened. Persisted at {@code world/&lt;dim&gt;/data/slashloot.dat}.
 *
 * <p>CompoundTag flavour, for MC 1.20.5 - 1.21.4. MC 1.21.5 removed {@code SavedData.Factory} in
 * favour of {@code SavedDataType} + {@code Codec}; that rewrite lives in {@code compat/store-codec}.
 * The on-disk shape is identical in both, and unchanged from 0.1.x, so saves migrate untouched.
 *
 * <p>Every band gets this exact public API — {@code mc-src} is written against it and nothing else.
 */
public class SlashLootState extends SavedData {

    private final Map<Long, PlayerLootEntry> blocks = new HashMap<>();
    private final Map<UUID, PlayerLootEntry> entities = new HashMap<>();

    public static SavedData.Factory<SlashLootState> factory() {
        return new SavedData.Factory<>(SlashLootState::new, SlashLootState::load, null);
    }

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
        SlashLootState state = storage.get(factory(), SlashLootCore.MOD_ID);
        if (state != null) return state;
        SlashLootState legacy = storage.get(factory(), SlashLootCore.LEGACY_ID);
        if (legacy == null) return storage.computeIfAbsent(factory(), SlashLootCore.MOD_ID);
        HolderLookup.Provider registries = level.registryAccess();
        SlashLootState copy = load(legacy.save(new CompoundTag(), registries), registries);
        storage.set(SlashLootCore.MOD_ID, copy);
        copy.setDirty();
        SlashLootCore.LOG.info("Migrated {} per-player loot data from the pre-rename save file",
                Ids.dimension(level));
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
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag blockList = new ListTag();
        for (Map.Entry<Long, PlayerLootEntry> e : blocks.entrySet()) {
            CompoundTag entry = new CompoundTag();
            entry.putLong("pos", e.getKey());
            entry.put("players", savePlayers(e.getValue(), registries));
            blockList.add(entry);
        }
        tag.put("blocks", blockList);

        ListTag entityList = new ListTag();
        for (Map.Entry<UUID, PlayerLootEntry> e : entities.entrySet()) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("uuid", e.getKey());
            entry.put("players", savePlayers(e.getValue(), registries));
            entityList.add(entry);
        }
        tag.put("entities", entityList);
        return tag;
    }

    private static ListTag savePlayers(PlayerLootEntry entry, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Map.Entry<UUID, LootContainer> e : entry.entries().entrySet()) {
            CompoundTag row = new CompoundTag();
            row.putUUID("uuid", e.getKey());
            row.putInt("size", e.getValue().getContainerSize());
            row.put("items", e.getValue().createTag(registries));
            list.add(row);
        }
        return list;
    }

    private static void loadPlayers(PlayerLootEntry target, ListTag list, HolderLookup.Provider registries) {
        for (int i = 0; i < list.size(); i++) {
            CompoundTag row = list.getCompound(i);
            UUID uuid = row.getUUID("uuid");
            int size = row.getInt("size");
            LootContainer container = target.newContainer(size > 0 ? size : 27);
            container.fromTag(row.getList("items", Tag.TAG_COMPOUND), registries);
            target.put(uuid, container);
        }
    }

    public static SlashLootState load(CompoundTag tag, HolderLookup.Provider registries) {
        SlashLootState state = new SlashLootState();
        ListTag blockList = tag.getList("blocks", Tag.TAG_COMPOUND);
        for (int i = 0; i < blockList.size(); i++) {
            CompoundTag entry = blockList.getCompound(i);
            PlayerLootEntry players = new PlayerLootEntry(state::setDirty);
            loadPlayers(players, entry.getList("players", Tag.TAG_COMPOUND), registries);
            state.blocks.put(entry.getLong("pos"), players);
        }
        ListTag entityList = tag.getList("entities", Tag.TAG_COMPOUND);
        for (int i = 0; i < entityList.size(); i++) {
            CompoundTag entry = entityList.getCompound(i);
            PlayerLootEntry players = new PlayerLootEntry(state::setDirty);
            loadPlayers(players, entry.getList("players", Tag.TAG_COMPOUND), registries);
            state.entities.put(entry.getUUID("uuid"), players);
        }
        return state;
    }
}

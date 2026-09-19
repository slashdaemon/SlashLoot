package dev.blockacademy.slashloot.store;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.blockacademy.slashloot.SlashLootCore;
import dev.blockacademy.slashloot.compat.Ids;
import dev.blockacademy.slashloot.core.LootContainer;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Per-dimension store of every player's personal copy of every naturally-generated container they
 * have opened. Persisted at {@code world/&lt;dim&gt;/data/slashloot.dat}.
 *
 * <p>Codec flavour, for MC 1.21.6 and up, where {@code SavedData.Factory} was replaced by
 * {@code SavedDataType} + {@code Codec}. The {@code SavedDataType} key type then changed again at
 * MC 26.1 (String to Identifier), which is the one line held in {@code compat/savedtype-*}.
 *
 * <p>Same on-disk shape as the CompoundTag flavour and as 0.1.x, so saves migrate untouched.
 *
 * <p>Every band gets this exact public API — {@code mc-src} is written against it and nothing else.
 */
public class SlashLootState extends SavedData {

    record SlotItem(int slot, ItemStack item) {
        static final Codec<SlotItem> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.INT.fieldOf("slot").forGetter(SlotItem::slot),
                ItemStack.CODEC.fieldOf("item").forGetter(SlotItem::item)
        ).apply(i, SlotItem::new));
    }

    record PlayerSlots(UUID player, int size, List<SlotItem> items) {
        static final Codec<PlayerSlots> CODEC = RecordCodecBuilder.create(i -> i.group(
                UUIDUtil.CODEC.fieldOf("uuid").forGetter(PlayerSlots::player),
                Codec.INT.fieldOf("size").forGetter(PlayerSlots::size),
                SlotItem.CODEC.listOf().fieldOf("items").forGetter(PlayerSlots::items)
        ).apply(i, PlayerSlots::new));
    }

    private record BlockEntryRec(long pos, List<PlayerSlots> players) {
        static final Codec<BlockEntryRec> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.LONG.fieldOf("pos").forGetter(BlockEntryRec::pos),
                PlayerSlots.CODEC.listOf().fieldOf("players").forGetter(BlockEntryRec::players)
        ).apply(i, BlockEntryRec::new));
    }

    private record EntityEntryRec(UUID uuid, List<PlayerSlots> players) {
        static final Codec<EntityEntryRec> CODEC = RecordCodecBuilder.create(i -> i.group(
                UUIDUtil.CODEC.fieldOf("uuid").forGetter(EntityEntryRec::uuid),
                PlayerSlots.CODEC.listOf().fieldOf("players").forGetter(EntityEntryRec::players)
        ).apply(i, EntityEntryRec::new));
    }

    public static final Codec<SlashLootState> CODEC = RecordCodecBuilder.create(i -> i.group(
            BlockEntryRec.CODEC.listOf().fieldOf("blocks").forGetter(SlashLootState::serializeBlocks),
            EntityEntryRec.CODEC.listOf().fieldOf("entities").forGetter(SlashLootState::serializeEntities)
    ).apply(i, SlashLootState::deserialize));

    public static final SavedDataType<SlashLootState> TYPE = StateType.create(CODEC);
    private static final SavedDataType<SlashLootState> LEGACY_TYPE = StateType.legacy(CODEC);

    private final Map<Long, PlayerLootEntry> blocks = new HashMap<>();
    private final Map<UUID, PlayerLootEntry> entities = new HashMap<>();

    public SlashLootState() {}

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
        SlashLootState state = storage.get(TYPE);
        if (state != null) return state;
        SlashLootState legacy = storage.get(LEGACY_TYPE);
        if (legacy == null) return storage.computeIfAbsent(TYPE);
        RegistryOps<Tag> ops = level.registryAccess().createSerializationContext(NbtOps.INSTANCE);
        SlashLootState copy = CODEC.parse(ops, CODEC.encodeStart(ops, legacy).getOrThrow()).getOrThrow();
        storage.set(TYPE, copy);
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

    private static SlashLootState deserialize(List<BlockEntryRec> blocks, List<EntityEntryRec> entities) {
        SlashLootState state = new SlashLootState();
        for (BlockEntryRec e : blocks) {
            PlayerLootEntry pe = state.blocks.computeIfAbsent(e.pos(),
                    k -> new PlayerLootEntry(state::setDirty));
            for (PlayerSlots ps : e.players()) pe.put(ps.player(), inflate(pe, ps));
        }
        for (EntityEntryRec e : entities) {
            PlayerLootEntry pe = state.entities.computeIfAbsent(e.uuid(),
                    k -> new PlayerLootEntry(state::setDirty));
            for (PlayerSlots ps : e.players()) pe.put(ps.player(), inflate(pe, ps));
        }
        return state;
    }

    private static LootContainer inflate(PlayerLootEntry owner, PlayerSlots ps) {
        LootContainer c = owner.newContainer(Math.max(1, ps.size()));
        for (SlotItem item : ps.items()) {
            if (item.slot() >= 0 && item.slot() < c.getContainerSize()) {
                c.setItem(item.slot(), item.item());
            }
        }
        return c;
    }

    private List<BlockEntryRec> serializeBlocks() {
        List<BlockEntryRec> out = new ArrayList<>();
        for (Map.Entry<Long, PlayerLootEntry> e : blocks.entrySet()) {
            out.add(new BlockEntryRec(e.getKey(), toPlayerSlotsList(e.getValue())));
        }
        return out;
    }

    private List<EntityEntryRec> serializeEntities() {
        List<EntityEntryRec> out = new ArrayList<>();
        for (Map.Entry<UUID, PlayerLootEntry> e : entities.entrySet()) {
            out.add(new EntityEntryRec(e.getKey(), toPlayerSlotsList(e.getValue())));
        }
        return out;
    }

    private static List<PlayerSlots> toPlayerSlotsList(PlayerLootEntry entry) {
        List<PlayerSlots> out = new ArrayList<>();
        for (Map.Entry<UUID, LootContainer> e : entry.entries().entrySet()) {
            out.add(toPlayerSlots(e.getKey(), e.getValue()));
        }
        return out;
    }

    private static PlayerSlots toPlayerSlots(UUID uuid, LootContainer c) {
        List<SlotItem> items = new ArrayList<>();
        for (int i = 0; i < c.getContainerSize(); i++) {
            ItemStack s = c.getItem(i);
            if (!s.isEmpty()) items.add(new SlotItem(i, s.copy()));
        }
        return new PlayerSlots(uuid, c.getContainerSize(), items);
    }
}

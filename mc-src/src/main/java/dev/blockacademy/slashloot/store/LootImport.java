package dev.blockacademy.slashloot.store;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.serialization.Dynamic;
import dev.blockacademy.slashloot.SlashLootCore;
import dev.blockacademy.slashloot.compat.Ids;
import dev.blockacademy.slashloot.core.LootContainer;
import net.minecraft.SharedConstants;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.fixes.References;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.loot.LootTable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Carries per-player state from a previous per-player loot mod over to SlashLoot: who already looted
 * each container, and what they left in it. Reads the 1.21.1 per-container file format (one gzipped
 * SavedData per container, {@code <c>/<cc>/<uuid>.dat}); the old mod need not be installed.
 *
 * <p><b>Matching is by loot table + seed, not position.</b> The old mod's blocks themselves are converted
 * back to vanilla elsewhere, keeping {@code LootTable} and {@code LootTableSeed}. A seed is a random
 * 64-bit value, so {@code (table, seed)} identifies a container wherever its build ends up: pasted at
 * an offset, in another dimension, or in a fresh world whose own containers carry different seeds.
 * No chunk is ever loaded to do the match.
 *
 * <p>Two steps. {@code /slashloot import <dir>} scans the files and, on {@code confirm}, writes an
 * index ({@code <world>/slashloot-import.json}, a key-to-file map, so no item data is copied).
 * Then, the first time any player touches a container SlashLoot has no entry for, {@link #claimInto}
 * looks its key up and, on a hit, reads that one file into the container's entry and drops the
 * key. That is a hash probe on the interaction path only — never from {@code unpackLootTable}, so
 * {@code Handling} stays side-effect free.
 *
 * <p>Version-neutral by construction: NBT is walked through DFU's {@link Dynamic}, item stacks are
 * brought up to this server's data version through vanilla's own DataFixer and then decoded with
 * {@link ItemStack#CODEC}. A dump from a 1.21.1 world therefore imports into a newer world too.
 */
public final class LootImport {

    /** Which kind of SlashLoot container an imported record may be claimed by. */
    public enum Category { BLOCK, ENTITY, POT }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final String INDEX_FILE = "slashloot-import.json";

    /** The file's {@code newType} (namespace stripped) to the SlashLoot category that may claim it. */
    private static final Map<String, Category> TYPES = Map.of(
            "chest", Category.BLOCK,
            "trapped_chest", Category.BLOCK,
            "barrel", Category.BLOCK,
            "shulker", Category.BLOCK,
            "minecart", Category.ENTITY,
            "pot", Category.POT);

    /** On-disk index. Plain JSON on purpose: it holds no item data, so it never needs upgrading. */
    static final class Index {
        String source = "";
        Map<String, Rec> records = new LinkedHashMap<>();
    }

    static final class Rec {
        String file;
        String type;
        String dimension;
        int[] position;
        int players;
    }

    private static Path cachedFor;
    private static Index cached;

    private LootImport() {}

    // ------------------------------------------------------------------ claim

    /**
     * Fills a brand-new container entry from imported data when a record matches this container's table and
     * seed. Call only when the entry was just created, before any player's copy is rolled. A failure is
     * logged and leaves the record in place, so the container simply behaves as if never imported.
     */
    public static void claimInto(ServerLevel level, Category category, ResourceKey<LootTable> table, long seed,
                                 PlayerLootEntry entry, int slots) {
        MinecraftServer server = level.getServer();
        Index index = index(server);
        if (index.records.isEmpty()) return;

        String key = key(Ids.of(table), seed);
        Rec rec = index.records.get(key);
        if (rec == null) return;

        Category recCategory = TYPES.get(rec.type);
        if (recCategory != category) {
            SlashLootCore.LOG.warn("Import: {} matches a {} record but is a {} here - not imported",
                    key, rec.type, category);
            return;
        }

        try {
            int imported = materialise(level, serverDir().resolve(index.source).resolve(rec.file), entry, slots);
            index.records.remove(key);
            save(server, index);
            SlashLootCore.LOG.info("Import: carried {} player(s) over to {} in {}",
                    imported, key, Ids.dimension(level));
        } catch (IOException | RuntimeException e) {
            SlashLootCore.LOG.warn("Import: could not read " + rec.file + " for " + key + " - not imported", e);
        }
    }

    private static int materialise(ServerLevel level, Path file, PlayerLootEntry entry, int slots) throws IOException {
        Dynamic<Tag> root = new Dynamic<>(NbtOps.INSTANCE, NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap()));
        int fileVersion = root.get("DataVersion").asInt(SharedConstants.WORLD_VERSION);
        Dynamic<Tag> data = payload(root);
        RegistryOps<Tag> ops = level.registryAccess().createSerializationContext(NbtOps.INSTANCE);

        Map<UUID, List<SlotStack>> leftovers = new HashMap<>();
        for (Dynamic<Tag> inv : data.get("inventories").asList(d -> d)) {
            Optional<UUID> player = uuid(inv.get("uuid").result());
            if (player.isEmpty()) continue;
            List<SlotStack> items = new ArrayList<>();
            for (Dynamic<Tag> item : inv.get("chest").get("Items").asList(d -> d)) {
                int slot = item.get("Slot").asInt(-1);
                if (slot < 0) continue;
                Dynamic<Tag> fixed = fileVersion < SharedConstants.WORLD_VERSION
                        ? DataFixers.getDataFixer().update(References.ITEM_STACK, item, fileVersion, SharedConstants.WORLD_VERSION)
                        : item;
                ItemStack.CODEC.parse(ops, fixed.getValue())
                        .resultOrPartial(err -> SlashLootCore.LOG.warn("Import: skipped an item in {}: {}", file.getFileName(), err))
                        .filter(s -> !s.isEmpty())
                        .ifPresent(s -> items.add(new SlotStack(slot, s)));
            }
            leftovers.put(player.get(), items);
        }

        // An opener with no inventory row looted it and then had their copy cleared: already looted.
        Set<UUID> players = new HashSet<>(leftovers.keySet());
        players.addAll(openers(data));

        for (UUID player : players) {
            List<SlotStack> items = leftovers.getOrDefault(player, List.of());
            int size = slots;
            for (SlotStack s : items) size = Math.max(size, s.slot() + 1);
            if (size > slots) {
                SlashLootCore.LOG.warn("Import: {} needs {} slots, container has {} - kept every item",
                        file.getFileName(), size, slots);
            }
            LootContainer c = entry.newContainer(size);
            for (SlotStack s : items) c.setItem(s.slot(), s.stack());
            entry.put(player, c);
        }
        return players.size();
    }

    private record SlotStack(int slot, ItemStack stack) {}

    // ------------------------------------------------------------------- scan

    /** What a scan found. {@link #records} is what {@code confirm} would stage. */
    public static final class Report {
        public int files;
        public int unreadable;
        public int noTableOrSeed;
        public int ambiguous;
        public int playersWithLeftovers;
        public int openersOnly;
        public final Map<String, Integer> staged = new TreeMap<>();
        public final Map<String, Integer> unsupported = new TreeMap<>();
        final Map<String, Rec> records = new LinkedHashMap<>();
        Path source;

        public int stagedTotal() {
            return records.size();
        }
    }

    /** Reads every data file under {@code dir}. No world state is touched. */
    public static Report scan(Path dir) throws IOException {
        Report report = new Report();
        report.source = dir;
        Map<String, Integer> seen = new HashMap<>();
        List<Path> files;
        try (Stream<Path> walk = Files.walk(dir)) {
            files = walk.filter(p -> p.getFileName().toString().endsWith(".dat") && Files.isRegularFile(p)).toList();
        }
        for (Path file : files) {
            report.files++;
            Dynamic<Tag> data;
            try {
                data = payload(new Dynamic<>(NbtOps.INSTANCE, NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap())));
            } catch (IOException | RuntimeException e) {
                report.unreadable++;
                continue;
            }
            String type = data.get("newType").asString("");
            if (type.isEmpty()) continue; // the old mod's own bookkeeping files (advancement / decay / refresh)
            type = type.substring(type.indexOf(':') + 1);
            if (!TYPES.containsKey(type)) {
                report.unsupported.merge(type, 1, Integer::sum);
                continue;
            }
            String table = data.get("table").asString("");
            long seed = data.get("seed").asLong(0L);
            if (type.equals("pot") && table.contains(":archaeology/")) {
                // Suspicious sand/gravel saved under the pot type. SlashLoot doesn't instance
                // brushable blocks, and no real pot carries an archaeology table, so it could never match.
                report.unsupported.merge("suspicious_block", 1, Integer::sum);
                continue;
            }
            if (table.isEmpty() || seed == 0L) {
                report.noTableOrSeed++;
                continue;
            }
            String key = key(table, seed);
            if (seen.merge(key, 1, Integer::sum) > 1) {
                // Two containers sharing a table and seed can't be told apart after conversion.
                if (report.records.remove(key) != null) report.ambiguous++;
                report.ambiguous++;
                continue;
            }

            Set<UUID> invPlayers = new HashSet<>();
            for (Dynamic<Tag> inv : data.get("inventories").asList(d -> d)) {
                uuid(inv.get("uuid").result()).ifPresent(invPlayers::add);
            }
            int leftovers = invPlayers.size();
            Set<UUID> openerOnly = new HashSet<>(openers(data));
            openerOnly.removeAll(invPlayers);
            report.playersWithLeftovers += leftovers;
            report.openersOnly += openerOnly.size();

            Rec rec = new Rec();
            rec.file = dir.relativize(file).toString().replace('\\', '/');
            rec.type = type;
            rec.dimension = data.get("dimension").asString("");
            rec.position = data.get("position").asIntStream().toArray();
            rec.players = leftovers + openerOnly.size();
            report.records.put(key, rec);
        }
        for (Rec rec : report.records.values()) report.staged.merge(rec.type, 1, Integer::sum);
        return report;
    }

    /** Replaces the staged index with a scan's records. */
    public static void stage(MinecraftServer server, Report report) throws IOException {
        Index index = new Index();
        // Relative to the server folder, so a world staged on one machine still claims on another.
        index.source = serverDir().relativize(report.source.toAbsolutePath().normalize())
                .toString().replace('\\', '/');
        index.records.putAll(report.records);
        save(server, index);
    }

    /** Staged records not yet claimed, by record type. */
    public static Map<String, Integer> pending(MinecraftServer server) {
        Map<String, Integer> out = new TreeMap<>();
        for (Rec rec : index(server).records.values()) out.merge(rec.type, 1, Integer::sum);
        return out;
    }

    /** Drops every staged record. Returns how many there were. */
    public static int discard(MinecraftServer server) throws IOException {
        Index index = index(server);
        int n = index.records.size();
        index.records.clear();
        save(server, index);
        return n;
    }

    // ---------------------------------------------------------------- helpers

    static String key(String table, long seed) {
        return table + "#" + seed;
    }

    /** Vanilla SavedData files nest the payload under {@code data}; tolerate a bare one too. */
    private static Dynamic<Tag> payload(Dynamic<Tag> root) {
        return root.get("data").result().orElse(root);
    }

    private static Set<UUID> openers(Dynamic<Tag> data) {
        Set<UUID> out = new HashSet<>();
        for (Dynamic<Tag> d : data.get("openers").asList(d -> d)) uuid(Optional.of(d)).ifPresent(out::add);
        return out;
    }

    private static Optional<UUID> uuid(Optional<Dynamic<Tag>> d) {
        return d.flatMap(x -> UUIDUtil.CODEC.parse(x).result());
    }

    /** The server's working directory; the import command only accepts folders inside it. */
    public static Path serverDir() {
        return Path.of("").toAbsolutePath().normalize();
    }

    private static Path indexPath(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve(INDEX_FILE).toAbsolutePath().normalize();
    }

    private static synchronized Index index(MinecraftServer server) {
        Path path = indexPath(server);
        if (!path.equals(cachedFor)) {
            Index loaded = new Index();
            if (Files.exists(path)) {
                try {
                    Index read = GSON.fromJson(Files.readString(path), Index.class);
                    if (read != null && read.records != null) loaded = read;
                } catch (IOException | RuntimeException e) {
                    SlashLootCore.LOG.warn("Import: could not read " + INDEX_FILE + ", ignoring it", e);
                }
            }
            cachedFor = path;
            cached = loaded;
        }
        return cached;
    }

    private static synchronized void save(MinecraftServer server, Index index) throws IOException {
        Path path = indexPath(server);
        Files.writeString(path, GSON.toJson(index));
        cachedFor = path;
        cached = index;
    }
}

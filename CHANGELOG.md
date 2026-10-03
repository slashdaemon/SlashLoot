# Changelog

All notable changes to SlashLoot. Dates are YYYY-MM-DD.

## 0.5.2 — 2026-10-02

Double chests now follow vanilla's rules exactly. Thanks to TheArchictect for the detailed report.

- **Fixed: an unnamed double chest showed "Chest" as its title.** It now shows "Large Chest", like
  vanilla.
- **Fixed: a double chest opened if you clicked its free half while the other half was blocked**
  (for example by a solid block or a sitting cat on top of it). It now stays shut, like vanilla.
- **Fixed: a double chest with only one half locked opened from the unlocked half.** Both halves now
  have to be unlockable.
- **Fixed: if only one half was renamed, its name could be lost.** The menu now uses vanilla's name
  rule: the first named half wins, and with no names it's "Large Chest".
- The two halves now appear in the same top-and-bottom order as vanilla. On some existing double
  chests your two halves may swap places once; nothing in them is lost.
- Applies to every file (Fabric, NeoForge and Forge).

## 0.5.1 — 2026-10-02

- **Fixed: the Fabric builds now work in singleplayer, LAN and e4mc worlds.** Every Fabric file was
  marked server-only, so Fabric skipped SlashLoot in any game client, including the built-in server
  that runs a singleplayer world. To use it there, put SlashLoot and Fabric API in your own `mods/`
  folder. Friends who join your LAN world don't need it.
- On a dedicated server nothing changes: only the server needs SlashLoot, and players connect with an
  unmodified game. Having it installed on the client as well (for example in a modpack) is harmless.
- Only the 13 Fabric files are new. The Forge and NeoForge files already worked in singleplayer and
  stay at 0.5.0.

## 0.5.0 — 2026-09-20

- **New: Forge 1.20.1.** A new `slashloot-0.5.0+mc1.20.1-forge.jar` for MinecraftForge 1.20.1 (Forge 47
  or later). Like every other build it is server-side only: players connect with an unmodified game.
- **The same Forge file also runs on NeoForge 1.20.1**, so 1.20.1 NeoForge servers can use SlashLoot
  for the first time.
- Everything behaves exactly as on Fabric: the same per-player loot, config file, commands, and save
  format.
- Nothing changed on the other 24 files; they are rebuilt at 0.5.0 unchanged from 0.4.0.

## 0.4.0 — 2026-09-19

- **Fixed: Minecraft 1.20.1 servers crashed at startup.** Every 1.20.1 build since 0.2.0 carried a
  chest-minecart hook aimed at a method 1.20.1 doesn't have, and Fabric refused to load it. 1.20.1 now
  uses the same hook as every other version. Sorry to anyone who hit this.
- **New: Minecraft 26.3**, on Fabric (needs Fabric API 0.161.0+26.3) and NeoForge. The NeoForge 26.3
  build is marked beta because NeoForge 26.3 itself has no stable release yet; SlashLoot runs correctly
  on it. (This was prepared as 0.3.3, which was never published.)
- **Mod id is now `slashloot`**, and JARs are named `slashloot-<version>+mc<band>-<loader>.jar`.
  **Remove the old `slashlootr-*.jar` from your mods folder when you add this one.** Because the id
  changed, a server with both files would load SlashLoot twice.
- **Config file is now `config/slashloot.json`.** On first start, an existing `config/slashlootr.json`
  is moved to the new name. If both files exist, the new one wins and the old one is left alone.
- **Save file is now `slashloot.dat`.** On first use in each dimension, SlashLoot reads the old
  `slashlootr.dat`, copies it over, and writes the new file at the next world save. The log shows a
  `Migrated <dimension> per-player loot data` line when this happens. The old file is left on disk as
  a backup and never read again; delete it once you're happy.
- Nothing else changed. Loot, commands (`/slashloot`), config options and behaviour are the same as
  0.3.2.

## 0.3.3 — 2026-09-19

**Minecraft 26.3 support, on Fabric and NeoForge.** No gameplay changes — this release exists to
mark 26.3 support.

- **New files:** `slashlootr-0.3.3+mc26.3-fabric.jar` (needs Fabric API 0.161.0+26.3) and
  `slashlootr-0.3.3+mc26.3-neoforge.jar`. Every other band is rebuilt unchanged at 0.3.3; 24 JARs
  in all (13 Fabric + 11 NeoForge).
- **The NeoForge 26.3 build is marked beta**, because NeoForge 26.3 itself has no stable release
  yet. SlashLoot runs correctly on it; the tag reflects the loader, and it moves to release once a
  stable NeoForge 26.3 lands.
- Nothing in SlashLoot needed changing: 26.3 kept every vanilla code path SlashLoot hooks. Chests,
  trapped chests, barrels and chest minecarts are instanced per player as before; blocklisted loot
  tables and unsupported containers still fall back to vanilla.
- For builders: 26.3 is a new quarantined composite under `versions/26.3/` (Gradle 9.6.1, Loom
  1.17.21, JDK 25), built by `./gradlew build263` and included in `buildAll`. The publish scripts
  force the NeoForge 26.3 JAR to `beta` via `BETA_ONLY_BANDS`.

## 0.3.2 — 2026-09-09

**Cobblemon's Gilded Chests are now real per-player containers.** Reported issue: Cobblemon's
Gilded Chest blocks weren't recognized, so players got shared vanilla loot instead of personal
copies, consumed on first open like any unrecognized container.

- **All seven real Gilded Chest colors are recognized** (`gilded_chest`, and the blue/black/yellow/
  white/green/pink variants) and served the same as a vanilla chest — 27 slots, lock/rename/pruning
  support all apply automatically since Cobblemon's chest already extends the same vanilla base
  class ours does.
- **The Gimmighoul Chest (the Pokémon mimic) is deliberately left alone.** It shares Cobblemon's
  chest code internally but isn't a real container — recognizing it would have let SlashLoot's
  block-click handler steal the interaction before Cobblemon's own mimic-reveal logic ever runs.
  Matched by block id, not by type, specifically so this one is never included.
- **Side effect worth knowing:** opening a real Gilded Chest now also counts toward the vanilla
  "Open Chest" stat and can anger nearby piglins, exactly like a vanilla chest would — this is
  intentional, not a bug.
- Any other modded container can still be recognized broadly via the existing
  `handleUnknownContainers` config option, or excluded per-loot-table via `lootTableBlocklist`.

## 0.3.1 — 2026-09-09

**Vanilla containers stay vanilla containers, even locked ones.** Follow-up fixes from
**TheArchictect**'s second round of testing on 0.3.0.

- **Locked chests and barrels are locked again.** SlashLoot was checking the `Lock` NBT tag for
  shulker boxes but not chests or barrels, so a locked chest or barrel opened for anyone regardless
  of what was in their hand. All three now honor `Lock` the same way vanilla does.
- **Shulker boxes obstructed by a block in front of them no longer open.** This was a second, separate
  bug hiding behind the same line of code as the lock check above — fixed independently.
- **Opening a container for the first time fires the `player_generates_container_loot` advancement
  trigger**, matching vanilla. Datapacks and advancements keyed on it now work against SlashLoot
  containers.
- **Renamed containers keep their name.** A chest, barrel, or shulker box renamed on an anvil before
  anyone loots it now shows that name in the personal-copy menu instead of the generic "Chest".
- **Pruning no longer deletes live loot on a config change.** Adding a loot table to the blocklist, or
  toggling `enabled` off temporarily, could make the background prune (or `/slashloot prune`) treat a
  still-standing container as gone and drop its stored per-player loot. Pruning now only removes an
  entry once the container itself is actually gone.
- **Piglins get angry again, and open-container stats advance again.** SlashLoot's interaction handler
  replaces vanilla's chest/barrel/shulker `use()` entirely, which was silently skipping
  `PiglinAi.angerNearbyPiglins` and the `stat.openChest`/`openBarrel`/`openShulkerBox` stats. Both are
  reproduced now.
- **Personal container menus close when you walk away**, or if the real container is destroyed while
  you're looking at your copy of it — matching vanilla's distance/validity behavior, which SlashLoot's
  containers never implemented before this release.

## 0.3.0 — 2026-08-29

**Built to sit inside somebody else's modpack.** Almost everything here is SlashLoot learning to do
less: leaving containers it does not own alone, handing blacklisted loot back to vanilla, letting
chests behave like chests. And it now runs on NeoForge, across every version Fabric already had.

Thanks to **TheArchictect** on CurseForge, who reported four of these.

### Containers SlashLoot does not own are left alone

- **Blacklisted dimensions and loot tables no longer serve empty containers.** If you put a
  dimension or a loot table on a blocklist, that container now behaves exactly as it would with
  SlashLoot uninstalled: shared vanilla loot, working hoppers, working comparators. Previously the
  blocklist stopped SlashLoot from serving the container but did not stop it from suppressing the
  vanilla roll, so nobody filled it and it stayed empty forever.
- **Modded containers are no longer broken by association.** The vanilla method SlashLoot hooks is
  shared by every container type in the game, including ones from other mods that it has no idea how
  to serve. Those are now left completely untouched. If you *want* SlashLoot to instance unknown
  modded containers, set `handleUnknownContainers: true`.
- **Container size is read from the container** instead of being assumed to be 27 slots, so a modded
  chest with a different inventory size gets a personal copy of the right shape.

### Chests behave like chests again

- **Lids animate.** Chest and shulker lids open, barrels flip their `open` state, and both halves of
  a double chest animate together. The open sound now comes from vanilla, so it plays once.
- **Trapped chests emit redstone again.** This had been silently broken since 0.1.0.
- Turn it off with `delegateContainerAnimation: false` if it conflicts with something.

### It cleans up after itself

Stored loot for a container is dropped when that container is destroyed, so a long-running server
does not accumulate dead entries. Three layers cover the ways a container can vanish: breaking it,
destroying the minecart or boat carrying it, and a low-cost background sweep that catches
explosions, pistons, and world edits. Tune with `cleanupOnBreak`, `pruneIntervalTicks`, and
`pruneBatchSize`.

### You can ask it what it decided

Set `debugLogging: true` and SlashLoot logs one line per container: position, loot table, whether it
served a personal copy or handed the container to vanilla, and why.

```
[SlashLoot] block chest @ minecraft:overworld [123,64,-45] table=minecraft:chests/simple_dungeon -> INSTANCE reason=instanced slots=27
[SlashLoot] block minecraft:hopper @ minecraft:overworld [110,-60,100] table=minecraft:chests/village/village_weaponsmith -> VANILLA reason=unsupported_container:minecraft:hopper
```

Repeat verdicts are deduplicated, so hopper polling cannot flood the log. Built for exactly the job
of working out why a container in a big pack behaves the way it does.

### NeoForge, and Minecraft 26.2

- **NeoForge builds** for 1.20.6, 1.21 through 1.21.1, 1.21.2 through 1.21.3, 1.21.4, 1.21.5,
  1.21.6 through 1.21.8, 1.21.9 through 1.21.10, 1.21.11, 26.1.2, and 26.2. Same mod, same
  behaviour, same save data.
- **Minecraft 26.2** on both loaders.
- JARs are now named `slashlootr-<version>+mc<band>-<loader>.jar`. Mind the suffix when you pick one.

Two gaps worth knowing about, both outside our control: NeoForge has no 1.20.1 or 1.20.5 line, so
Fabric covers those alone; and NeoForge has published no stable 21.6, 21.7 or 21.9 build, so
Minecraft 1.21.6 through 1.21.8 and 1.21.9 through 1.21.10 are served by the 21.8 and 21.10 builds.

### Version coverage correction

**If you run Minecraft 1.21.5 on Fabric, the old download did not work and you should take the new
1.21.5 file.** Releases up to 0.1.2 advertised the 1.21.4 JAR as covering 1.21.5. It never could:
Minecraft removed the save-data API that build relies on at 1.21.5, one version earlier than the
metadata assumed. 1.21.5 now has its own build on both loaders.

### New commands

`/slashloot forget all` now works (it previously printed instructions to stop the server and delete
a file by hand). Also new: `/slashloot prune`, `/slashloot stats`, and `/slashloot reload`.

### Config

Every key is optional and defaults sensibly, so a config file from an older version keeps working.
`/slashloot reload` re-reads it without a restart.

```json
{
  "enabled": true,
  "dimensionBlocklist": [],
  "lootTableBlocklist": [],
  "handleUnknownContainers": false,
  "delegateContainerAnimation": true,
  "playOpenCloseSounds": true,
  "cleanupOnBreak": true,
  "pruneIntervalTicks": 6000,
  "pruneBatchSize": 256,
  "debugLogging": false
}
```

Mod id, config path, and the per-dimension save file are unchanged, so existing server data carries
over untouched.

### Under the hood

- The nine per-band source forks are gone. Every band from 1.20.5 up now compiles one shared source
  tree plus small per-generation compatibility shims, so a fix is written once instead of nine
  times. 1.20.1 stays a documented fork; it predates the interfaces the rest of the tree relies on.
- That shared tree is loader-neutral behind a single seam, which is why NeoForge needed one adapter
  and no changes to the mod logic or the mixins at all.
- The 26.1.2 mixin config now declares JAVA_25, matching the JDK it is compiled with. It was
  shipping JAVA_21.
- Break cleanup no longer trusts the break event. NeoForge fires its version before the break and
  allows it to be cancelled, unlike Fabric, so the position is re-checked a tick later instead of
  being dropped on faith.

### Verified

Band 1.21.1 was checked end to end on both loaders: instanced containers, blacklist fallback,
unsupported-container fallback, commands, config reload, and decision logging. All 22 band and
loader combinations build clean.

## 0.1.2 — 2026-05-31

Branding pass: the project is now published as **SlashLoot** to keep the listing distinct and unambiguous.

### Changed

- **Admin command renamed `/slashlootr` → `/slashloot`** across all bands, to match the new project name.
- Command feedback messages now prefixed `SlashLoot:` instead of `SlashLootr:`.
- **License is now CC-BY-4.0** (was MIT) — `LICENSE`, every band's `fabric.mod.json`, and the listing descriptions updated to match.
- Listing descriptions (Modrinth/CurseForge) and README rewritten to remove third-party-mod comparisons.

### Unchanged (intentionally)

- Mod id, package, config file (`config/slashlootr.json`), and the per-dimension save file (`world/<dim>/data/slashlootr.dat`) keep the `slashlootr` identifier so existing server data is preserved across the update.

## 0.1.1 — 2026-05-25

Two new bands covering Mojang's late-2025 / early-2026 rename storm.

### Added

- **Band F: MC 1.21.11** — picks up the renames Mojang shipped at 1.21.11 that broke Band E on that version:
  - `net.minecraft.world.entity.vehicle.ChestBoat` → `…vehicle.boat.ChestBoat`
  - `net.minecraft.world.entity.vehicle.MinecartChest` → `…vehicle.minecart.MinecartChest`
  - `net.minecraft.world.entity.vehicle.MinecartHopper` → `…vehicle.minecart.MinecartHopper`
  - `net.minecraft.world.entity.vehicle.AbstractMinecartContainer` → `…vehicle.minecart.AbstractMinecartContainer`
  - `ResourceLocation` → `Identifier` (renamed in place at `net.minecraft.resources`)
  - `ResourceKey#location()` → `ResourceKey#identifier()`
  - `Level#random` (field) → `Level#getRandom()` (method)
  - `CommandSourceStack#hasPermission(int)` → `Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)` predicate
  - `SimpleContainer.addListener` removed → replaced with a `DirtyContainer extends SimpleContainer` that overrides `setChanged()` to mark the SavedData dirty
  - `SavedDataType` first arg still `String` (changes to `Identifier` in 26.1)
- **Band G: MC 26.1.2 ("Tiny Takeover")** — same rename surface as Band F plus:
  - **Quarantined**: own Gradle 9.4 wrapper, Loom 1.15.5, JDK 25 toolchain. Lives under `versions/26.1.2/` with its own gradlew. Main composite shells out via the `build26` task.
  - **Unobfuscated**: no `mappings loom.officialMojangMappings()` — Mojang names ship directly.
  - `SavedDataType` first arg now `Identifier` (via `Identifier.fromNamespaceAndPath`).
  - `SeedDeriver` inlined into the band's package since the main composite's `:common` subproject isn't reachable from the quarantine.

### Changed

- **Loom bumped 1.11 → 1.13.6** across the main composite. Verified backwards-compatible with Bands A through E. Required to add 1.21.11 as a regular subproject.
- **Band E (1.21.9) coverage narrowed** in publish scripts: was claimed to cover 1.21.9–1.21.11; now claims only 1.21.9 and 1.21.10 since 1.21.11 needs Band F's rename surface.
- **`build.gradle` root**: added `build26` `Exec` task and a quarantined-libs collect step in `buildAll` so the 10 JARs land together in `build/release/`.

### Runtime verified

- Band C (1.21.1): verified with two players on two machines.
- Bands A, B, D, E, F, G: compile-clean only.

---

## 0.1.0 — 2026-05-24

Initial implementation. Eight Minecraft version bands shipping out of the gate.

### Added

**Core mechanic** (all bands)
- Server-side per-player instanced loot for naturally-generated containers. Each player who opens a chest with a `LootTable` NBT tag receives a freshly-rolled personal inventory; one player's looting leaves the chest full for everyone else.
- Hook 1: mixin on the vanilla loot-roll method cancels the "lazy bake into the world container" pass — the chest's `LootTable` and `LootTableSeed` NBT tags stay intact across server restarts.
- Hook 2: Fabric `UseBlockCallback` + `UseEntityCallback` intercept right-click before vanilla menu-creation runs, build (or load) a per-player `SimpleContainer`, and open a vanilla `ChestMenu` / `ShulkerBoxMenu` / `HopperMenu` backed by it.
- Per-player loot seed: `containerSeed XOR player.uuid.msb XOR rotL(player.uuid.lsb, 17)` — same player + same container = same loot across re-opens.

**Container coverage**
- Chests, trapped chests, barrels, shulker boxes (block containers).
- Chest minecarts, hopper minecarts, chest boats (entity containers).
- Double chests: each half tracks its own per-player loot independently; opening either half presents a combined 54-slot `CompoundContainer` view, canonicalized by lower-packed `BlockPos`.

**Persistence**
- `world/<dimension>/data/slashlootr.dat`. Two top-level maps: `blocks` (keyed by packed `BlockPos`) and `entities` (keyed by entity UUID). Survives server restart.
- `ContainerListener` on every personal `SimpleContainer` marks the `SavedData` dirty on any slot change.

**Admin & config**
- `/slashlootr forget here|at <pos>|player <name>` (op-only).
- `config/slashlootr.json` with `dimensionBlocklist`, `lootTableBlocklist`, `playOpenCloseSounds`.

**Multi-version build infrastructure**
- Multi-project Gradle build patterned on `slashdaemon/TipSign`. `common/` holds version-agnostic logic (just `SeedDeriver`); each band under `versions/<MC>/` is a self-contained subproject.
- `./gradlew buildAll` produces 8 JARs in `build/release/`.

### Per-band specifics

> See [`docs/ARCHITECTURE.md` § Per-band specifics](docs/ARCHITECTURE.md#per-band-specifics) for full file-level diffs.

| Band | MC versions | Loom | Java | Notable rewrites vs. 1.21.1 baseline |
| ---- | ----------- | ---- | ---- | ------------------------------------- |
| **A** | 1.20.1 | 1.11.x | 17 | `RandomizableContainer` interface didn't exist — mixin targets `RandomizableContainerBlockEntity` class. `ContainerEntity` interface didn't exist — split into separate `MixinAbstractMinecartContainer` + `MixinChestBoat`. Loot-table fields are private — added three `@Accessor` mixins (`AccessorRandomizableContainerBlockEntity`, `AccessorAbstractMinecartContainer`, `AccessorChestBoat`). `ResourceLocation` everywhere (no `ResourceKey<LootTable>`). `SavedData.computeIfAbsent(loader, factory, id)` 3-arg form, `save(CompoundTag)` without `HolderLookup.Provider`. `SimpleContainer.fromTag/createTag` without `HolderLookup.Provider`. |
| **B** | 1.20.5 | 1.11.x | 21 | Same source as Band C — compiles clean because 1.20.5 introduced `RandomizableContainer` interface, `ContainerEntity` interface, `ResourceKey<LootTable>`, and `HolderLookup.Provider` parameters. |
| **C** | 1.21, 1.21.1 | 1.11.x | 21 | The baseline. 1.21 shares 1.21.1's source tree via `sourceSets.main.java.srcDirs`. |
| **D** | 1.21.2, 1.21.4 | 1.11.x | 21 | `ContainerEntity.getLootTable()` → `getContainerLootTable()` (and the seed equivalent). Two-method rename in `EntityInteractionHandler` + `MixinContainerEntity`. |
| **E** | 1.21.6, 1.21.9 | 1.11.x | 21 | `SavedData.Factory<T>` removed; `SavedDataType<T>` + `Codec<T>` is the only registration path. Rewrote `SlashLootrState` with `RecordCodecBuilder` and a sparse `SlotItem(int slot, ItemStack item)` codec (encodes only non-empty slots). `PlayerLootEntry` lost its NBT methods in favor of a codec helper. |

### Build infrastructure

- Loom bumped from 1.9 → 1.10 → 1.11 as needed for newer MC versions.
- Fabric API versions per band cross-referenced against `slashdaemon/TipSign` and Modrinth's Fabric API release list.

### Not yet implemented

- Chest lid animation (open sound plays via `OpenSoundFx`, but the lid stays closed because we bypass `ContainerOpenersCounter`).
- `/slashlootr forget all` — currently emits an error suggesting the admin stop the server and delete `slashlootr.dat`.
- Decorated pots and suspicious sand/gravel (different open mechanic — brushing — would need separate hooks).
- Looted-state visual indicator (Lootr's gold→blue color change would require a companion client mod).
- Loot decay / re-roll (each player's loot is currently permanent by design).
- Runtime verification on LocalServer or a production deploy — code is compile-clean only.

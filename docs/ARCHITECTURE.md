# SlashLoot Architecture

A technical deep-dive into how SlashLoot delivers per-player loot for naturally-generated containers **without** registering custom blocks and **without** requiring a client install. This document is the canonical reference for the mod's design, the vanilla code paths it hooks, and the compat seams that absorb Mojang's API drift between MC 1.20.1 and 26.1.

If you only want to use the mod, [`README.md`](../README.md) is enough. This document is for contributors and for the next person who has to add an MC version.

---

## Table of contents

- [1. Overview & motivation](#1-overview--motivation)
- [2. The vanilla loot path (what we hook)](#2-the-vanilla-loot-path-what-we-hook)
- [3. The intercept layer](#3-the-intercept-layer)
- [4. Per-player view: rolling, seeding, presenting](#4-per-player-view-rolling-seeding-presenting)
- [5. Identity & keying](#5-identity--keying)
- [6. Persistence](#6-persistence)
- [7. Configuration & admin commands](#7-configuration--admin-commands)
- [8. Project layout & per-band variants](#8-project-layout--per-band-variants)
- [9. The compat axes](#9-the-compat-axes)
- [10. Comparison vs. Lootr / myLoot / LootrPlugin](#10-comparison-vs-lootr--myloot--lootrplugin)
- [11. Limitations and rationale](#11-limitations-and-rationale)
- [12. Future extensions](#12-future-extensions)
- [13. Adding a new band](#13-adding-a-new-band)

---

## 1. Overview & motivation

### The problem

Vanilla Minecraft generates loot containers (chests in mineshafts, barrels in villages, shulker boxes in ancient cities, chest minecarts on rails, chest boats in shipwrecks…) with a **`LootTable` NBT tag** and a **`LootTableSeed`**. The first time anyone opens one, vanilla rolls the loot table into the container's inventory and clears the tag. Whoever's there first takes everything; everyone else opens an empty chest.

On a multiplayer server, this is a race. The mod [Lootr](https://github.com/LootrMinecraft/Lootr) solves it by giving each player their own copy of the loot — but it does so by **registering custom block variants** (`LootrChestBlock`, `LootrBarrelBlock`, etc.) and swapping the vanilla block-state at the container's position. That swap:

- requires the mod on every client (vanilla clients see an unknown block and refuse to connect);
- breaks scoreboard selectors, datapack predicates, `/data` queries, structure saves, and any mod that scans for `minecraft:chest`;
- the Lootr maintainers themselves [publicly invite](https://modrinth.com/mod/lootr) plugin developers to write a server-only Fabric alternative — none has existed until now.

### The insight

The custom block is **not load-bearing**. Lootr uses it for three things:

1. Routing the player's right-click to its own handler (`useWithoutItem` on the custom block).
2. The gold→blue "looted" visual indicator.
3. Stashing some per-container state on the block entity.

None of those are required to deliver the actual user value. The real mechanic — "every player rolls the loot table once, sees their own inventory, server persists it" — is **pure server-side bookkeeping**. We can deliver it by intercepting two vanilla code paths:

1. **Cancel the vanilla loot roll** so the world's container never gets baked with loot.
2. **Substitute the menu** when a player opens it, presenting a per-player inventory backed by a fresh roll.

Both can be done without ever modifying world block-state. The chest stays a `minecraft:chest` with its `LootTable` NBT tag intact forever. Vanilla clients see a regular chest UI populated with whatever items the server syncs to their menu — exactly the same wire format as a normal chest. **No client install required.**

### Outcome

A server-side mod that delivers Lootr's headline feature across 10 Minecraft version bands (1.20.1 → 26.1.x) with zero client install requirement and zero vanilla block-state changes.

---

## 2. The vanilla loot path (what we hook)

### Block containers

The relevant vanilla class is `RandomizableContainerBlockEntity` (the abstract superclass of `ChestBlockEntity`, `BarrelBlockEntity`, `ShulkerBoxBlockEntity`, `TrappedChestBlockEntity`, etc.). Since MC 1.20.5 it also implements the `RandomizableContainer` interface.

The flow in vanilla:

```
Player right-clicks a chest
    ↓
ChestBlock#useWithoutItem  (or BarrelBlock#useWithoutItem, etc.)
    ↓
BaseContainerBlockEntity#getMenuProvider  →  returns this BlockEntity as the MenuProvider
    ↓
ServerPlayer#openMenu(provider)
    ↓
provider.createMenu(...)  →  calls into ChestMenu / BarrelMenu / etc.
    ↓
The menu reads inventory contents via Container#getItem(slot)
    ↓
RandomizableContainerBlockEntity#getItem(slot)  →  calls #unpackLootTable(player) FIRST
    ↓
unpackLootTable:
    if (lootTable != null) {
        loot = registry.getLootTable(lootTable);
        setLootTable(null);                          ← CLEARS THE TAG
        params = new LootParams.Builder(level).withParameter(ORIGIN, pos)...
        loot.fill(this, params, lootTableSeed);    ← BAKES INTO THIS BE
    }
```

The key insight: **`unpackLootTable` is called lazily on first inventory read**. It (1) reads the loot table NBT, (2) clears it, (3) rolls items into the world's container.

If we **cancel** `unpackLootTable` server-side:
- The loot is never rolled into the world container.
- The `LootTable` NBT tag is never cleared.
- Every subsequent open re-triggers the same flow — and we cancel again.
- The container is **permanently "unrolled" from vanilla's perspective**.

### Entity containers

The same lazy-roll pattern exists for chest minecarts, hopper minecarts, and chest boats. Since MC 1.20.5 these all implement `ContainerEntity` (interface) and the relevant method is `ContainerEntity#unpackChestVehicleLootTable(Player)`. In MC 1.20.1 there's no shared interface — `AbstractMinecartContainer` and `ChestBoat` each have their own `unpackLootTable(Player)` method.

---

## 3. The intercept layer

### One decision, two callers

Before either hook does anything, it asks **`core/Handling`** whether this container is one
SlashLoot serves. Both the mixin and the interaction handler call that function and nothing else,
which makes it structurally impossible for them to disagree.

That coupling is not tidiness — it is the fix for the two worst bugs in 0.1.x. The mixin used to
cancel the vanilla roll unconditionally while the handlers skipped anything blacklisted or
unrecognised, so those containers were rolled by nobody and opened empty forever. `Handling` returns
one of two verdicts:

- **`INSTANCE`** — the mixin cancels the vanilla roll, and the handler opens a personal container.
- **`VANILLA`** — the mixin does not cancel, the handler passes, and the container behaves exactly as
  if SlashLoot were not installed. Hopper extraction and comparator output come back with it.

`VANILLA` carries a reason (`dimension_blocklisted`, `table_blocklisted`,
`unsupported_container:<id>`, `no_loot_table`, `disabled`), which is what `debugLogging` prints.

`Handling` is reached from `unpackLootTable`, which hopper polling calls repeatedly, so it is
side-effect free and never force-loads a chunk to answer.

**Any new container type or skip condition belongs in `Handling`, never in a handler or mixin alone.**

### Personal containers delegate open/close

The personal container is a `LootContainerBase` subclass that forwards `startOpen`/`stopOpen` to the
real world container. Vanilla's `ContainerOpenersCounter` therefore runs normally: chest and shulker
lids animate, barrels flip their `open` blockstate, and trapped chests emit redstone. Vanilla's
`CompoundContainer` forwards both calls to each half, so double chests need no special handling.

With delegation on (the default) vanilla plays the open sound itself, so the mod's manual
`OpenSoundFx` is suppressed — otherwise it would play twice.

### Two hooks, both server-side

```
                  ┌────────────────────────────────────────┐
                  │  PLAYER RIGHT-CLICKS A CONTAINER       │
                  └────────────────────────────────────────┘
                                    │
                                    ▼
         ┌──────────────────────────────────────────────────────┐
         │  Hook 1: loader use-block / use-entity event           │
         │  (the EARLIEST event we can register against)        │
         └──────────────────────────────────────────────────────┘
                                    │
                ┌───────────────────┴───────────────────┐
                ▼                                       ▼
       Container has LootTable                Container has no LootTable
       NBT tag (naturally gen'd)              (player-placed)
                │                                       │
                ▼                                       ▼
   Build per-player SimpleContainer          Return InteractionResult.PASS
   (rolled or loaded from SavedData)         → vanilla handles normally
                │
                ▼
   serverPlayer.openMenu(
       new SimpleMenuProvider(
           (id, inv, p) -> ChestMenu.threeRows(id, inv, perPlayerContainer),
           Component.translatable("container.chest")
       )
   )
                │
                ▼
   Return InteractionResult.SUCCESS  ← short-circuits the vanilla open path

   ════════════════════════════════════════════════════════════════════

   Meanwhile, if anything in vanilla EVER reaches a path that would call
   unpackLootTable on the world container...

         ┌──────────────────────────────────────────────────────┐
         │  Hook 2: Mixin on unpackLootTable                    │
         │  @Inject(at = HEAD, cancellable = true)              │
         │  → ci.cancel() if server-side AND lootTable != null  │
         └──────────────────────────────────────────────────────┘

   The world container is NEVER baked with loot. The LootTable NBT tag is
   NEVER cleared. The container stays "naturally generated forever."
```

### Why both hooks are necessary

Hook 1 alone is *almost* enough — but vanilla can call `unpackLootTable` from paths we don't intercept (hopper pulls, command-block `/data merge`, mod interactions). Hook 2 is the safety net: even if some other code path triggers a vanilla read, the loot still never bakes.

Hook 2 alone is **not** enough — if we cancel `unpackLootTable` but don't substitute the menu, the player opens an empty chest and we don't serve their per-player loot at all.

### Hook 1 in code

```java
// mc-src/.../handler/ContainerInteractionHandler.java
// The loader has already filtered to a ServerPlayer on a ServerLevel (LoaderBridge).
public static InteractionResult interact(
        ServerPlayer player, ServerLevel level, InteractionHand hand, BlockHitResult hit) {

    if (player.isSpectator()) return InteractionResult.PASS;
    if (player.isShiftKeyDown() && !player.getMainHandItem().isEmpty()) return InteractionResult.PASS;

    BlockPos pos = hit.getBlockPos();
    BlockEntity be = level.getBlockEntity(pos);

    // The ONE question. Blocklists, container classification and slot sizing all live here,
    // and the mixin below asks exactly the same thing.
    Handling.Decision decision = Handling.forBlock(level, pos, be);
    Handling.logBlock(level, pos, be, decision);
    if (!decision.instanced()) return InteractionResult.PASS;   // vanilla handles it, untouched

    // Player-dependent gates. Vanilla refuses to open in these cases too, so passing here
    // can never leave a container unrolled.
    if (be instanceof ShulkerBoxBlockEntity sbe && !sbe.canOpen(player)) return InteractionResult.PASS;
    if (be instanceof ChestBlockEntity && ChestBlock.isChestBlockedAt(level, pos)) return InteractionResult.PASS;

    // ... build the per-player container, point its animation at the world container, openMenu
}
```

### Hook 2 in code

```java
// mc-src/.../mixin/MixinRandomizableContainer.java
@Mixin(RandomizableContainer.class)
public interface MixinRandomizableContainer {
    @Inject(method = "unpackLootTable", at = @At("HEAD"), cancellable = true)
    default void slashloot$cancelVanillaRoll(Player player, CallbackInfo ci) {
        RandomizableContainer self = (RandomizableContainer) this;
        if (!(self instanceof BlockEntity be)) return;
        Level level = be.getLevel();
        if (level == null || level.isClientSide()) return;
        // Same question the handler asks. Cancel ONLY for containers we will actually serve —
        // this injection fires for every RandomizableContainer implementor in the game.
        if (Handling.instancesBlock(level, be.getBlockPos(), be)) {
            ci.cancel();
        }
    }
}
```

Note what is *not* here: no blocklist check, no container-type check. Both live in `Handling`, which
is the whole point — an earlier version duplicated those conditions in the handler only, and
containers the handler skipped were left cancelled and permanently empty.

The entity-container equivalent (`MixinContainerEntity`) hooks `unpackChestVehicleLootTable` with the same shape.

---

## 4. Per-player view: rolling, seeding, presenting

### Building the per-player container

When the use-callback fires for a naturally-generated container:

1. Look up the per-dimension `SlashLootState` (the persistent store).
2. Look up the `PlayerLootEntry` for this container's identity (block pos or entity UUID).
3. Look up the `SimpleContainer` for this player's UUID.
4. **If absent**: roll a fresh one via `LootTable#fill` using a player-derived seed, register a `ContainerListener` that marks the state dirty on any slot change, store it.
5. Open a vanilla `ChestMenu` (or `ShulkerBoxMenu` / `HopperMenu`) backed by that container.

### Seed derivation

```java
// common/.../SeedDeriver.java
public static long derive(long containerSeed, UUID player) {
    return containerSeed
         ^ player.getMostSignificantBits()
         ^ Long.rotateLeft(player.getLeastSignificantBits(), 17);
}
```

Properties:
- **Stable**: same player + same container = same loot. If a player closes their menu with items still inside, the same items are still there on re-open (because we persist; the seed determinism is a back-stop, not the primary mechanism).
- **Differentiated**: two different players get materially different loot from the same container.

### Menu types

`ContainerKind` enum maps each container type to its slot count, display name, and `MenuProvider` factory:

```java
public enum ContainerKind {
    CHEST           (27, "container.chest",      false),
    BARREL          (27, "container.barrel",     false),
    SHULKER         (27, "container.shulkerBox", true),   // → ShulkerBoxMenu
    DOUBLE_CHEST    (54, "container.chestDouble",false),  // → ChestMenu.sixRows
    MINECART_CHEST  (27, "container.minecart",   false),
    MINECART_HOPPER ( 5, "container.hopper",     false),  // → HopperMenu
    CHEST_BOAT      (27, "container.chestBoat",  false);
    // ...
    public MenuProvider menuProvider(Container container) { ... }
}
```

### Server-only client compatibility

Vanilla clients consume `ScreenHandler.sendAllDataToRemote()` sync packets and slot updates. They don't know — and don't need to know — what `Container` backs the server's `Menu`. As long as the server sends them slot contents, the client renders the UI and echoes clicks back to the server. The server validates clicks against its `Container`. Our `SimpleContainer` Just Works.

---

## 5. Identity & keying

| Container kind                                | Identity key                                  | Persists across      |
| --------------------------------------------- | --------------------------------------------- | -------------------- |
| Single chest / trapped chest / barrel         | `(ResourceKey<Level>, BlockPos)`              | Chunk reload, restart |
| Shulker box                                   | `(ResourceKey<Level>, BlockPos)`              | Chunk reload, restart |
| Double chest                                  | Each half keyed independently by its own `BlockPos`; combined view via `CompoundContainer` | Same |
| Chest minecart / hopper minecart / chest boat | `(ResourceKey<Level>, entity.UUID)`           | Entity NBT persistence |

### Why `(dim, BlockPos)` is safe

- Chunk reload preserves block-entity NBT, so the same `BlockPos` always refers to the same naturally-generated container (the `LootTable` tag remains).
- If a player **breaks** a naturally-generated chest and **places** a new one at the same position, the new chest has no `LootTable` NBT tag (player-placed chests don't get loot tables). Our use-callback checks for the tag and returns `PASS`, so the player-placed chest behaves vanilla.
- We never store the loot table or seed in our `SavedData` — we read them from the live BlockEntity at intercept time. The BE always has them (we cancel the only path that would clear them).

### Double-chest handling

Two adjacent `ChestBlockEntity`s with the same `ChestType.LEFT` / `RIGHT` form one logical 54-slot inventory. We treat each half independently:

1. On right-click, detect the pair via `ChestBlock.getConnectedDirection(state)`.
2. Canonicalize ordering by packed `BlockPos`: lower-packed is "first", higher is "second" — deterministic regardless of which half the player clicked.
3. Look up (or roll) per-player loot for **both halves separately** — each half rolls its own loot table independently, matching vanilla semantics.
4. Wrap both `SimpleContainer`s in a vanilla `CompoundContainer(first, second)` and open `ChestMenu.sixRows`.

Breaking one half leaves the other half's per-player data intact (since they're stored under different `BlockPos` keys).

### Entity identity

For chest minecarts and chest boats, `entity.getUUID()` is stable across chunk unload/reload because Mojang persists it in the entity NBT. Moving the entity around the world doesn't change its UUID.

---

## 6. Persistence

### Where it lives

`world/<dimension>/data/slashloot.dat` — one per dimension, lazily created on first use, accessed via `ServerLevel#getDataStorage().computeIfAbsent(...)`.

### Schema (Bands A–D: NBT-based)

```
SlashLootState (CompoundTag)
├── blocks: ListTag<CompoundTag>
│   └── { pos: long, players: ListTag<CompoundTag> {
│             uuid: UUID, size: int, items: ListTag (SimpleContainer.createTag) } }
└── entities: ListTag<CompoundTag>
    └── { uuid: UUID, players: ListTag<CompoundTag> {
              uuid: UUID, size: int, items: ListTag (SimpleContainer.createTag) } }
```

Implementation: `compat/store-nbt/.../store/SlashLootState.java`, shared by Bands B–D. Band A carries its own copy that drops the `HolderLookup.Provider` arguments (1.20.1 predates them).

### Schema (Bands E–G: Codec-based)

From 1.21.6 the store has to go through `Codec<T>` because `SavedData.Factory` was removed in 1.21.5. Equivalent schema — the same bytes on disk — expressed declaratively (`compat/store-codec/`):

```java
record SlotItem(int slot, ItemStack item) {
    static final Codec<SlotItem> CODEC = RecordCodecBuilder.create(i -> i.group(
        Codec.INT.fieldOf("slot").forGetter(SlotItem::slot),
        ItemStack.CODEC.fieldOf("item").forGetter(SlotItem::item)
    ).apply(i, SlotItem::new));
}

record PlayerSlots(UUID player, int size, List<SlotItem> items) { ... }
record BlockEntryRec(long pos, List<PlayerSlots> players) { ... }
record EntityEntryRec(UUID uuid, List<PlayerSlots> players) { ... }

public static final Codec<SlashLootState> CODEC = RecordCodecBuilder.create(i -> i.group(
    BlockEntryRec.CODEC.listOf().fieldOf("blocks").forGetter(SlashLootState::serializeBlocks),
    EntityEntryRec.CODEC.listOf().fieldOf("entities").forGetter(SlashLootState::serializeEntities)
).apply(i, SlashLootState::deserialize));

public static final SavedDataType<SlashLootState> TYPE = StateType.create(CODEC);
```

`StateType` is the one line that differs again at 26.1, where `SavedDataType`'s first argument
became an `Identifier` — hence the `savedtype-string` / `savedtype-id` axis. The resulting file name
is unchanged either way.

Note the **sparse** representation: each `PlayerSlots` only stores the slot indices that are non-empty, plus the original container size. Robust against future container-size changes, and avoids encoding 26 empty `ItemStack`s per minimally-looted barrel.

### Dirty marking

Every personal container overrides `setChanged()`:

```java
// mc-src/.../core/LootContainerBase.java
@Override
public void setChanged() {
    super.setChanged();
    if (onDirty != null) onDirty.run();   // marks the owning SavedData dirty
}
```

This replaced an `addListener` callback in 0.2.0. `SimpleContainer#addListener` was removed in
MC 26.1, whereas overriding `setChanged` works identically on every band we ship — so the change
deleted a compat axis rather than adding one.

Mojang's `SavedData` framework persists dirty saved data on world save (every ~10s by default, and at shutdown).

---

## 7. Configuration & admin commands

### `config/slashloot.json`

Created on first launch with defaults. Re-readable at runtime with `/slashloot reload`; missing keys
take their defaults, so a file written by an older version keeps working.

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

| Key | Effect |
| --- | ------ |
| `enabled` | Master switch. `false` makes the mod inert; every container behaves like vanilla. |
| `dimensionBlocklist` | Dimension ids served as ordinary shared vanilla loot. |
| `lootTableBlocklist` | Loot table ids served as ordinary shared vanilla loot. |
| `handleUnknownContainers` | Instance containers that implement `RandomizableContainer` / `ContainerEntity` but are not a type SlashLoot recognises. Off by default — leaving unknown modded containers to vanilla is the compatible choice. |
| `delegateContainerAnimation` | Forward open/close to the real container so lids animate and trapped chests emit redstone (section 3). |
| `playOpenCloseSounds` | Manual open sound. Only consulted when `delegateContainerAnimation` is `false`. |
| `cleanupOnBreak` | Drop a container's stored loot when it is destroyed. |
| `pruneIntervalTicks` | How often the background sweep looks for containers that no longer exist. `0` disables it. |
| `pruneBatchSize` | Entries examined per tick during a sweep, so a large save cannot spike the tick budget. |
| `debugLogging` | Log one decision line per container. Emitted from `Handling` itself, so it always reflects what actually happened; repeat verdicts for a container are deduplicated through a bounded LRU so hopper polling cannot flood the log. |

Blocklists are compared as plain namespaced strings. That is deliberate: `ResourceLocation` was
renamed to `Identifier` at 1.21.11, and keeping the comparison on strings means the shared config
code never has to name either type.

### Cleanup, in three layers

No single hook covers every way a container can disappear, so `handler/CleanupHandler` uses three:

1. **Player break** — the loader's block-break event, immediate. The common case.
2. **Entity removal** — `MixinEntityRemoved` on `Entity#setRemoved`, filtered on
   `RemovalReason#shouldDestroy()`. A mixin rather than a loader event because both loaders only
   surface entity removal in forms that conflate destruction with chunk unload, which would wipe a
   chest minecart's loot every time it went out of render range.
3. **Background prune** — a rotating cursor over stored positions, `pruneBatchSize` per tick. For
   each entry whose chunk happens to be loaded, it re-asks `Handling` and drops the entry if the
   container is gone. This is what catches explosions, pistons, and world edits. Entries in unloaded
   chunks are left alone and revisited later.

### `/slashloot` commands

All require permission level 2 (op).

| Command | Effect |
| ------- | ------ |
| `/slashloot forget here` | Wipe every player's personal loot at the block you are looking at |
| `/slashloot forget at <x> <y> <z>` | Same, by coordinate |
| `/slashloot forget player <player>` | Wipe one player's loot at every container in this dimension |
| `/slashloot forget all` | Wipe every stored container in this dimension |
| `/slashloot prune` | Run a full sweep now; reports removed and skipped-because-unloaded counts |
| `/slashloot stats` | Stored block/entity/player-copy counts for this dimension |
| `/slashloot reload` | Re-read `config/slashloot.json` |


---

## 8. Project layout & per-band variants

**There is one copy of the mod logic.** Bands B through G compile the same shared tree; a band's
build file names only the compat variants it needs. This replaced nine full per-band source forks
in 0.2.0 - a fix used to cost nine edits, and drifted between bands in practice.

```
SlashLoot/
|- build.gradle                Root: Loom plugin, fabricBands list, buildAll, build26
|- gradle/fabric-band.gradle   Composes a band JAR from the shared tree + its variants
|- common/                     SeedDeriver - plain Java, no MC types
|- mc-src/                     ALL shared mod logic, ONE copy
|   `- src/main/java/dev/blockacademy/slashloot/
|       |- SlashLootCore.java         boot(LoaderBridge) - loader-agnostic entrypoint
|       |- loader/LoaderBridge.java    the ONLY Fabric/NeoForge seam
|       |- core/Handling.java          THE decision function (section 3)
|       |- core/LootContainerBase.java dirty tracking + open/close delegation
|       |- core/ContainerKind.java     menu type + slot sizing
|       |- core/LootRoller.java        LootTable#fill wrapper
|       |- core/OpenSoundFx.java       fallback open sound
|       |- core/DebugLog.java          deduplicated decision logging
|       |- handler/ContainerInteractionHandler.java
|       |- handler/EntityInteractionHandler.java
|       |- handler/CleanupHandler.java  break hook + background prune
|       |- command/SlashLootCommand.java
|       |- config/SlashLootConfig.java
|       |- store/PlayerLootEntry.java
|       `- mixin/{MixinRandomizableContainer,MixinContainerEntity,MixinEntityRemoved}.java
|- compat/                     per-generation seams - a few dozen lines each
|- loader-fabric/              FabricBridge + entrypoint + fabric.mod.json + mixins.json
`- versions/
    |- 1.20.1-fabric/          Band A - self-contained fork (see below)
    |- <band>-fabric/          build.gradle + gradle.properties ONLY
    `- 26.1.2/                 Band G - quarantined composite (own wrapper, JDK 25)
```

A band's entire build file:

```gradle
plugins { id "fabric-loom" }
ext.slashloot = [ids: "location", vehicle: "legacy", store: "nbt", open: "player"]
apply from: "${rootDir}/gradle/fabric-band.gradle"
```

### A note on names

The mod id, package (`dev.blockacademy.slashloot`), config file (`config/slashloot.json`) and save
file (`world/<dim>/data/slashloot.dat`) all use `slashloot`. Releases up to 0.3.3 used an older id
for the mod, the config file and the save file; `SlashLootCore.LEGACY_ID` holds it and is used for
nothing but migration:

- `SlashLootConfig#load` moves an old config file to the new name if no new one exists.
- `SlashLootState#get` reads the old save file when no new one exists, registers that data under
  the new id and marks it dirty, so the next world save writes the new file. The old file is left
  on disk and never read again once the new one exists.

The save data holds no class names, so the package rename needs no migration of its own.

---

## 9. The compat axes

Each axis is one vanilla API that changed at one MC version. A band picks a variant per axis; the
variant holds only the differing calls.

| Axis | Variants | What changed, and when |
| ---- | -------- | ---------------------- |
| `ids` | `location`, `identifier` | `ResourceLocation` renamed in place to `Identifier`, and `ResourceKey#location()` became `#identifier()`, at **1.21.11**. Also carries the `Perms` predicate, since `hasPermission(2)` became `Commands.hasPermission(LEVEL_GAMEMASTERS)` at the same version. |
| `vehicle` | `legacy`, `container`, `moved` | `ContainerEntity#getLootTable` became `#getContainerLootTable` at **1.21.2**; minecarts moved to `vehicle.minecart` and boats to `vehicle.boat` at **1.21.11**. |
| `store` | `nbt`, `codec` | `SavedData.Factory` + `CompoundTag` replaced by `SavedDataType` + `Codec` at **1.21.6**. |
| `savedtype` | `string`, `id` | The `SavedDataType` first argument became an `Identifier` at **26.1**. Only meaningful when `store: codec`. |
| `open` | `player`, `containeruser` | `Container#startOpen`/`#stopOpen` took a `ContainerUser` instead of a `Player` from **1.21.9**. Only surfaced in 0.2.0, when personal containers began delegating open/close. |

### Band to variant matrix

| Band | MC | ids | vehicle | store | savedtype | open |
| ---- | -- | --- | ------- | ----- | --------- | ---- |
| A | 1.20.1 | *(fork - Java 17)* | | | | |
| B | 1.20.5-1.20.6 | location | legacy | nbt | - | player |
| C | 1.21, 1.21.1 | location | legacy | nbt | - | player |
| D | 1.21.2, 1.21.4 | location | container | nbt | - | player |
| E | 1.21.6 | location | container | codec | string | player |
| E | 1.21.9 | location | container | codec | string | containeruser |
| F | 1.21.11 | identifier | moved | codec | string | containeruser |
| G | 26.1.2 | identifier | moved | codec | id | containeruser |

### Band G - MC 26.1.2 ("Tiny Takeover")

Quarantined under `versions/26.1.2/` with its own Gradle 9.4 wrapper, Loom 1.15.5 and a JDK 25
toolchain, because that Loom is incompatible with the main composite's 1.13.6. The root `build26`
task shells out to it. 26.1 ships **unobfuscated**, so it declares no mappings. It still compiles the
same shared tree, reached by relative path since the parent project model is not visible from inside
the quarantine.

### Band A - MC 1.20.1 (the deliberate fork)

`versions/1.20.1-fabric/` keeps its own full copy of the sources, and is the one place a fix must be
ported by hand. 1.20.1:

- has **no `RandomizableContainer` interface** - the mixin targets `RandomizableContainerBlockEntity`
- has **no `ContainerEntity` interface** - minecarts and chest boats are unrelated types, needing
  separate mixins on `AbstractMinecartContainer` and `ChestBoat`
- stores loot tables as **`ResourceLocation`**, not `ResourceKey<LootTable>`
- keeps those fields **private**, so reads go through `@Accessor` mixins
- resolves tables via `MinecraftServer#getLootData()` rather than `reloadableRegistries()`
- uses the three-arg `SavedData.computeIfAbsent` and a `save(CompoundTag)` with no `HolderLookup`

Sharing it would mean threading an opaque loot-reference abstraction through every band to serve one
legacy Fabric-only version. Its `Handling` keeps the same contract and the same reason strings as the
shared one, so porting a change is mechanical.
## 10. Comparison vs. Lootr / myLoot / LootrPlugin

| Aspect                              | Lootr                          | myLoot                         | LootrPlugin (Paper)        | **SlashLoot** |
| ----------------------------------- | ------------------------------ | ------------------------------ | -------------------------- | --------------- |
| Loader                              | Fabric, NeoForge, Forge        | Fabric                         | Paper plugin               | Fabric          |
| Required on clients                 | Yes                            | Yes                            | No                         | **No**          |
| Touches vanilla block-state         | Yes (`LootrChestBlock` swap)   | Yes (`MyLootChestBlock` swap)  | No                         | **No**          |
| Vanilla `/data` / selectors work    | No (it's a different block)    | No                             | Yes                        | **Yes**         |
| Per-player loot                     | ✓                              | ✓                              | ✓                          | ✓               |
| Block + entity containers           | ✓                              | ✓                              | ✓                          | ✓               |
| Looted-state visual indicator       | ✓ (custom block texture)       | ✓                              | ✗                          | ✗               |
| Loot decay / re-roll                | ✓                              | —                              | ?                          | ✗ (by design)   |
| Decorated pots / suspicious blocks  | ✓                              | ✗                              | ?                          | ✗               |
| MC versions covered                 | 1.12, 1.16–1.21.x              | 1.18–1.20.x                    | 1.18–26.1.x                | **1.20.1, 1.20.5–26.1.x** |

The one trade-off SlashLoot accepts in exchange for vanilla compatibility:

1. **No looted-state visual.** Adding it would require a companion client mod, undermining the "vanilla clients" benefit.

Lid animation was the other one until 0.2.0, when personal containers began delegating
`startOpen`/`stopOpen` to the real world container — so vanilla's `ContainerOpenersCounter` runs
after all. See section 3.

---

## 11. Limitations and rationale

### Hoppers don't pull from instanced containers

The world container is always empty by construction, so a hopper attached to a naturally-generated
chest extracts nothing. This is inherent to the approach and matches Lootr. It applies **only** to
containers SlashLoot actually instances - a container on a blocklist, or one the mod does not
handle, is left entirely to vanilla and hoppers work normally on it.

### Comparator output is 0 from instanced containers

Same cause, same scope.

### Decorated pots and suspicious sand/gravel

Not covered. They use the brushing mechanic rather than a container menu, so they would need their
own hooks.

### No looted-state visual

Marking already-opened containers with a colour or texture change would require a companion client
mod, which would undermine the whole "vanilla clients connect unmodified" premise.

### The background prune only sees loaded chunks

A container destroyed by an explosion in a chunk that never loads again keeps its stored entry.
Entries are small, and the sweep reclaims them on any later pass that finds the chunk loaded.
Player-broken containers are cleaned up immediately regardless.

### Runtime verification coverage

Band C (1.21.1) is verified end-to-end. The other bands are compile-clean; the shared source tree
means they run the same code, but their compat variants are exercised only by the compiler.
## 12. Future extensions

In rough order of effort vs. value:

1. **NeoForge builds.** The `LoaderBridge` seam exists and everything below it is loader-neutral;
   what remains is a `NeoForgeBridge` mapping the five hooks onto `NeoForge.EVENT_BUS`, a
   `neoforge.mods.toml`, and per-band ModDevGradle build files. NeoForge is Mojang-mapped, so the
   mixins apply unchanged.
2. **Two-player client verification on the remaining bands.** Band C is verified end-to-end; the
   others share its source but their compat variants are only compiler-checked.
3. **Comparator output** mixin for instanced containers, returning a sensible per-player value.
4. **Decorated pots / suspicious sand & gravel** — separate mixin hooks on the brushing path.
5. **Companion client mod** for the looted-state visual indicator. Optional install; server keeps working with vanilla clients.
6. **Loot decay / re-roll**: optional config knob to clear a player's personal copy after N MC days, prompting a re-roll on next open.
7. **Per-player seed strategy** as a config option (deterministic vs. true-random-per-open).

---

## 13. Adding a new band

Adding a band is normally four small edits and a build.

### Step-by-step

1. Add `include "versions:<MC>-fabric"` to `settings.gradle`.
2. Add the band to the `fabricBands` list in the root `build.gradle`.
3. Create `versions/<MC>-fabric/gradle.properties` with `minecraft_version` and
   `fabric_api_version`. Add `java_version` / `mixin_compat` only if the band is not on JDK 21.
4. Create `versions/<MC>-fabric/build.gradle`:

   ```gradle
   plugins { id "fabric-loom" }
   ext.slashloot = [ids: "...", vehicle: "...", store: "...", open: "..."]
   apply from: "${rootDir}/gradle/fabric-band.gradle"
   ```

   Pick each variant from the matrix in section 9 - take the row for the nearest earlier version,
   then move any axis whose split point the new version has crossed.

5. `./gradlew :versions:<MC>-fabric:build`.

**If it compiles, you are done.** No source is copied and no band is forked.

### When it does not compile

The compiler is telling you a new drift axis appeared. Resist forking the tree:

1. Identify the smallest API surface that changed.
2. Create `compat/<axis>-<variant>/src/main/java/...` holding only the differing calls, plus a second
   variant for the existing behaviour if the axis is new.
3. Add the key to the `assert` block and the `shared` list in `gradle/fabric-band.gradle`.
4. Set the key on every band.

That is exactly how the `open` axis appeared in 0.2.0, when delegating `startOpen`/`stopOpen`
surfaced the `Player` to `ContainerUser` change at 1.21.9.

### Verifying a new band

`./gradlew buildAll` must collect the expected JAR count. Then run the headless functional pass from
[`CLAUDE.md`](../CLAUDE.md#verification) - a hopper under a container drives `unpackLootTable`, which
is the exact path the mixins hook, so the blacklist and unsupported-container fallbacks can both be
checked without a client.
## See also

- [`README.md`](../README.md) — user-facing install and usage.
- [`CHANGELOG.md`](../CHANGELOG.md) — release history.
- [`CLAUDE.md`](../CLAUDE.md) — project guidance for future Claude sessions, including the LocalServer verification recipe.
- [Lootr source](https://github.com/LootrMinecraft/Lootr) — the prior art whose hook strategy informed this design.
- [myLoot source](https://github.com/spoorn/myLoot) — Fabric-specific reference for the loot-table-bearing container conversion approach.

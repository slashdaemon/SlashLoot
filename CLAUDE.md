# CLAUDE.md — SlashLootr

Server-side per-player loot mod, published as **SlashLoot**. **Fabric + NeoForge.** **No custom blocks.** **No client install required.** Vanilla-compatible alternative to [Lootr](https://github.com/LootrMinecraft/Lootr).

## Why this exists

Lootr/myLoot achieve per-player chests by swapping vanilla blocks for custom `LootrChestBlock`/`MyLootChestBlock` variants. That breaks vanilla compatibility and requires clients to install the mod. SlashLoot does the same job by intercepting two vanilla code paths server-side — the chest stays a `minecraft:chest` and vanilla clients connect normally.

## How it works

1. **Mixin** `RandomizableContainer#unpackLootTable` (and `ContainerEntity#unpackChestVehicleLootTable`) → cancel the vanilla loot roll. The chest's `LootTable`/`LootTableSeed` NBT tags persist forever; the world container stays "unrolled."
2. **Loader event on player right-click** → look up or roll a personal container from a per-dimension `SavedData`, then `openMenu(...)` a vanilla `ChestMenu` backed by it.

Per-player seed: `XOR(containerSeed, player.uuid.msb, rotL(player.uuid.lsb, 17))` — deterministic, so re-opening shows what you left.

Persistence: `world/<dim>/data/slashlootr.dat`. Two maps inside: `blocks` (keyed by packed `BlockPos`) and `entities` (keyed by entity UUID).

### `core/Handling` is the single decision point

**Both the mixins and the interaction handlers ask `Handling` and nothing else** whether a container
is ours. That is not a style choice — it is the fix for the two worst bugs in 0.1.x, where the mixin
cancelled the vanilla roll for containers the handlers then refused to serve, leaving them
permanently empty. A `VANILLA` verdict means the mixin does not cancel and the container behaves as
if SlashLoot were absent.

**If you add a new container type or a new skip condition, it goes in `Handling`.** Never add a
condition to a handler or a mixin alone.

`Handling` runs from `unpackLootTable`, which hopper polling reaches repeatedly, so it must stay
side-effect free and must never force-load a chunk.

## Build

Java 21 for the 1.20.x–1.21.x bands (Java 17 target for Band A). Bands G, J and K (26.x) need JDK 25 via their own wrappers.

```bash
export JAVA_HOME="/c/Users/slash/AppData/Roaming/PrismLauncher/java/java-runtime-delta"
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew buildAll                        # all 24 band/loader JARs → build/release/
./gradlew :versions:1.21.1-fabric:build   # single Fabric band
./gradlew :versions:1.21.1-neoforge:build # single NeoForge band
./gradlew build26                         # quarantined Band G (26.1.x, both loaders)
./gradlew build262                        # quarantined Band J (26.2.x, both loaders)
./gradlew build263                        # quarantined Band K (26.3.x, both loaders)
```

Verify Prism's JDK still aliases as `java-runtime-delta` (Prism rotates these — check `ls ~/AppData/Roaming/PrismLauncher/java/` if Gradle complains about the toolchain).

## Layout

One shared source tree, composed per band. **There are no per-band copies of the mod logic** —
a fix is written once.

```
SlashLootr/
├── common/                     SeedDeriver — plain Java, no MC types
├── mc-src/                     ALL shared mod logic, ONE copy (bands B–G)
│   └── src/main/java/dev/blockacademy/slashlootr/
│       ├── SlashLootrCore.java         boot(LoaderBridge)
│       ├── loader/LoaderBridge.java    the ONLY Fabric/NeoForge seam
│       ├── core/Handling.java          THE decision function (read this first)
│       ├── core/LootContainerBase.java dirty tracking + open/close delegation
│       ├── core/{ContainerKind,LootRoller,OpenSoundFx,DebugLog}.java
│       ├── handler/{ContainerInteraction,EntityInteraction,Cleanup}Handler.java
│       ├── command/SlashLootCommand.java
│       ├── config/SlashLootrConfig.java
│       ├── store/PlayerLootEntry.java
│       └── mixin/{MixinRandomizableContainer,MixinContainerEntity,MixinEntityRemoved}.java
├── compat/                     per-generation seams, a few dozen lines each
│   ├── ids-location/           ≤1.21.10  ResourceKey#location(), hasPermission(2)
│   ├── ids-identifier/         ≥1.21.11  ResourceKey#identifier(), Commands.hasPermission
│   ├── vehicle-legacy/         ≤1.21.1   ContainerEntity#getLootTable
│   ├── vehicle-container/      1.21.2+   #getContainerLootTable
│   ├── vehicle-moved/          ≥1.21.11  + vehicle.minecart / vehicle.boat packages
│   ├── store-nbt/              ≤1.21.4   SavedData.Factory + CompoundTag
│   ├── store-codec/            ≥1.21.5   SavedDataType + Codec
│   ├── savedtype-string/       ≤1.21.11  SavedDataType(String, …)
│   ├── savedtype-id/           ≥26.1     SavedDataType(Identifier, …)
│   ├── open-player/            ≤1.21.8   Container#startOpen(Player)
│   └── open-containeruser/     ≥1.21.9   Container#startOpen(ContainerUser)
├── loader-fabric/              FabricBridge + entrypoint + fabric.mod.json
├── loader-neoforge/            NeoForgeBridge + @Mod entrypoint + neoforge.mods.toml
│   └── variants/break-*/       NeoForge-only axis: BreakEvent moved at 26.1
├── gradle/fabric-band.gradle   composes a Fabric band from each band's variant list
├── gradle/neoforge-band.gradle same, for NeoForge
└── versions/
    ├── 1.20.1-fabric/          Band A — SELF-CONTAINED FORK (see below)
    ├── <band>-<loader>/        build.gradle + gradle.properties only
    ├── 26.1.2/                 Band G — quarantined composite, band-fabric + band-neoforge
    ├── 26.2/                   Band J — same shape, forked from 26.1.2
    └── 26.3/                   Band K — same shape, forked from 26.2 (Gradle 9.6, Loom 1.17)
```

`mc-src/` names no loader type. The single seam is `loader/LoaderBridge` — five hooks (use-block,
use-entity, register-commands, block-break, server-tick) plus the config directory — which each
loader entrypoint implements and hands to `SlashLootrCore.boot(...)`.

A band's whole build file is its variant list:

```gradle
plugins { id "fabric-loom" }
ext.slashloot = [ids: "location", vehicle: "legacy", store: "nbt", open: "player"]
apply from: "${rootDir}/gradle/fabric-band.gradle"
```

### Band A is deliberately a fork

`versions/1.20.1-fabric/` keeps its own full copy of the sources. MC 1.20.1 predates the
`RandomizableContainer` and `ContainerEntity` interfaces, stores loot tables as `ResourceLocation`
rather than `ResourceKey<LootTable>`, and keeps those fields private (hence the `@Accessor` mixins).
Sharing it would mean an opaque loot-reference abstraction across every band to serve one legacy
Fabric-only version. **Changes to `mc-src` must be ported to Band A by hand** — its `Handling` keeps
the same contract and the same reason strings, so the port is mechanical.

## Currently shipping

24 JARs (13 Fabric + 11 NeoForge), all via `./gradlew buildAll`. Artifacts are
`slashlootr-<ver>+mc<band>-<loader>.jar`.

**NeoForge coverage differs from Fabric, for reasons outside our control:**

- NeoForge has no 1.20.1 outside the legacy MDG plugin, and no 1.20.5 line, so it starts at 1.20.6.
- NeoForge 21.6, 21.7 and 21.9 have **no stable builds at all** — every published `21.6.x` /
  `21.9.x` is a `-beta` (checked against `maven.neoforged.net`). MC 1.21.6–1.21.8 ride the 21.8
  build, and 1.21.9–1.21.10 ride the 21.10 build.
- **NeoForge 26.3 is beta-only** (`26.3.0.x-beta`). The publish scripts force that JAR to `beta`
  through `BETA_ONLY_BANDS`; drop the entry and re-pin once a stable 26.3 build lands.
- **1.21.5 is its own band on both loaders.** `SavedData.Factory` was removed at 1.21.5, not 1.21.6
  — the 1.21.4 JAR cannot run there, though 0.1.x advertised it for exactly that.

| Band dir | Loader | MC covered | ids | vehicle | store | savedtype | open |
| -------- | ------ | ---------- | --- | ------- | ----- | --------- | ---- |
| 1.20.1-fabric | F | 1.20.1 | *(fork — Java 17)* | | | | |
| 1.20.5-fabric | F | 1.20.5–1.20.6 | location | legacy | nbt | — | player |
| 1.20.6-neoforge | N | 1.20.6 | location | legacy | nbt | — | player |
| 1.21-fabric | F | 1.21 | location | legacy | nbt | — | player |
| 1.21.1-fabric | F | 1.21.1 | location | legacy | nbt | — | player |
| 1.21.1-neoforge | N | 1.21–1.21.1 | location | legacy | nbt | — | player |
| 1.21.2-fabric | F | 1.21.2–1.21.3 | location | container | nbt | — | player |
| 1.21.3-neoforge | N | 1.21.2–1.21.3 | location | container | nbt | — | player |
| 1.21.4-fabric / -neoforge | F+N | 1.21.4 | location | container | nbt | — | player |
| 1.21.5-fabric / -neoforge | F+N | 1.21.5 | location | container | codec | string | player |
| 1.21.6-fabric | F | 1.21.6–1.21.8 | location | container | codec | string | player |
| 1.21.8-neoforge | N | 1.21.6–1.21.8 | location | container | codec | string | player |
| 1.21.9-fabric | F | 1.21.9–1.21.10 | location | container | codec | string | containeruser |
| 1.21.10-neoforge | N | 1.21.9–1.21.10 | location | container | codec | string | containeruser |
| 1.21.11-fabric / -neoforge | F+N | 1.21.11 | identifier | moved | codec | string | containeruser |
| 26.1.2 (both) | F+N | 26.1.2 | identifier | moved | codec | id | containeruser |
| 26.2 (both) | F+N | 26.2 | identifier | moved | codec | id | containeruser |
| 26.3 (both) | F+N | 26.3 | identifier | moved | codec | id | containeruser |

Where each split lands: `getContainerLootTable` at **1.21.2**; `SavedDataType`+`Codec` at
**1.21.5**; `startOpen(ContainerUser)` at **1.21.9**; `Identifier` + vehicle package move at
**1.21.11**; `SavedDataType(Identifier)` at **26.1**. NeoForge adds one axis of its own:
`BlockEvent.BreakEvent` became `event.level.block.BreakBlockEvent` at **26.1** (`nfbreak`).

## Adding a new band

1. `include "versions:<MC>-<loader>"` in `settings.gradle` and add it to `fabricBands` or
   `neoforgeBands` in the root `build.gradle`.
2. Create `versions/<MC>-<loader>/gradle.properties`. Fabric: `minecraft_version`,
   `fabric_api_version`. NeoForge: `minecraft_version`, `neoforge_version`, plus the
   `neoforge_range` / `minecraft_range` Maven Version Ranges that go into `neoforge.mods.toml`.
   Add `java_version` / `mixin_compat` only if the band is not on JDK 21.
3. Create `versions/<MC>-<loader>/build.gradle` with the variant list, applying
   `gradle/fabric-band.gradle` or `gradle/neoforge-band.gradle` — pick the row from the table above
   whose splits the new version is on.
4. Build. **If it compiles, you are done.** If it does not, the compiler is telling you a new drift
   axis appeared: add a `compat/<axis>-<variant>/` directory (or, for a loader-only difference,
   `loader-neoforge/variants/<axis>-<variant>/`) holding only the differing calls, add the key to
   the composer, and set it on every band. Do not fork the whole tree.

**Before pinning a NeoForge version, check `maven.neoforged.net` rather than a table.** Several
widely-circulated matrices claim stable NeoForge exists for 21.6 / 21.9; it does not.

## Verification

**Build gate:** `./gradlew buildAll` must collect 24 JARs into `build/release/`.

**Headless functional pass** (no client needed — a hopper under a container triggers
`unpackLootTable`, which is the exact path the mixins hook). Boot a bare Fabric server with the
band's JAR + Fabric API, then over RCON:

```
setblock <p> minecraft:chest{LootTable:"minecraft:chests/simple_dungeon",LootTableSeed:1L}
setblock <p below> minecraft:hopper[facing=down]
data get block <p>          # instanced: LootTable tag SURVIVES, hopper stays empty
                            # blacklisted/unsupported: tag GONE, hopper holds real vanilla loot
```

Cover: chest (instanced), chest with its table in `lootTableBlocklist` (must fall back to vanilla),
`minecraft:hopper` with a loot table (unsupported container — must fall back to vanilla), barrel,
chest minecart. Set `debugLogging: true` and check each verdict line reads correctly.

**Use non-default ports.** LocalServer is often running and holds 25565 / 25575; a test server on
the defaults fails to bind, and an RCON client on 25575 talks to LocalServer instead. Set
`server-port` / `rcon.port` (e.g. 25590 / 25591) in the test server's `server.properties`.

On NeoForge the same sweep runs through ModDevGradle's generated server, which needs no separate
install — `./gradlew :versions:1.21.1-neoforge:runServer`, with `run/eula.txt` and
`run/server.properties` (RCON on) created first.

**Client pass** (needs two players; LocalServer at `C:\Users\slash\Projects\LocalServer\`):

1. `/locate structure minecraft:mineshaft` → dig to a chest → `/data get block ~ ~ ~ LootTable` shows a table id
2. Player A opens it → notes items; Player B opens → **different items**; A re-opens → same as before
3. Restart the server → both players' loot persists; the `LootTable` tag is still on the block
4. Lid animates on open, and the open sound plays **once**
5. Redstone lamp beside a natural **trapped** chest powers while open
6. Break the chest → `/slashloot stats` block count drops by one
7. Place a player-crafted chest → no interception

Repeat for: trapped chest, barrel, shulker box, chest minecart, double chest (both lids), chest boat.

## Known limitations

- **Hoppers extract nothing** from naturally-generated containers SlashLoot instances (the world container is always empty). Matches Lootr. Blacklisted and unsupported containers are unaffected.
- **Comparator output reads 0** from those same containers, for the same reason.
- **No decay / re-roll** — per-player loot is permanent by design.
- **Prune skips unloaded chunks.** Entries for containers destroyed in chunks that never load again are not reclaimed; they are cheap and get dropped on the next pass that finds the chunk loaded.

## Repository

`slashdaemon/SlashLootr`. No `Co-Authored-By: Claude` trailer on commits. Not part of the TBA modpack — it is a server-side-only addon.

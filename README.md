# SlashLoot

Server-side per-player loot for naturally-generated containers in Minecraft. **Fabric, NeoForge, and Forge 1.20.1. No client install required. No custom blocks.**

## What it does

Each player who opens a naturally-generated chest, barrel, shulker box, chest minecart, or chest boat sees their own personal copy of the loot rolled from that container's loot table. One player's looting doesn't drain the chest for anyone else. No racing to dungeons; no empty mineshafts after the speedrunner went through.

Player-placed containers behave exactly as in vanilla.

## Why server-side

SlashLoot never touches block-state. The container stays a `minecraft:chest` (or barrel, shulker, minecart, etc.) forever, and every player gets their own copy entirely via server-side menu substitution. That means:

- **No client install.** Players connect with plain vanilla Fabric and never have to update when you do.
- **Nothing else breaks.** Scoreboard selectors, datapack predicates, `/data` queries, structure saves, and other mods that scan for `minecraft:chest` keep seeing ordinary vanilla blocks.

Architecture deep-dive: [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).

## Supported versions

25 JARs — 13 Fabric, 11 NeoForge, 1 Forge. Pick the one matching your server's Minecraft version
**and loader**. Files are named `slashloot-<version>+mc<band>-<loader>.jar`.

**Forge is 1.20.1 only**, the modpack LTS line. The same `-forge` JAR also runs on NeoForge 1.20.1
(NeoForge's 1.20.1 line is Forge 47.1.3 underneath), so 1.20.1 NeoForge servers use it too.

NeoForge coverage differs slightly from Fabric: NeoForge has no 1.20.5 line of its own, and no stable
builds for 21.6 / 21.7 / 21.9 — MC 1.21.6–1.21.8 and 1.21.9–1.21.10 are covered by the 21.8 and
21.10 builds instead.

The 26.1, 26.2 and 26.3 bands are quarantined builds with their own Gradle wrapper, JDK 25, and
Loom (Gradle 9.4 + Loom 1.15.5 for 26.1/26.2, Gradle 9.6 + Loom 1.17.21 for 26.3), because Mojang
ships 26.x unobfuscated. NeoForge 26.3 is still beta-only, so that JAR is published as beta.

| Minecraft | Loader | Fabric API (Fabric only) |
| --------- | ------ | ------------------------ |
| 1.20.1 | Fabric | 0.92.2+1.20.1 |
| 1.20.1 | Forge (also runs on NeoForge 1.20.1) | — |
| 1.20.5–1.20.6 | Fabric | 0.97.8+1.20.5 |
| 1.20.6 | NeoForge | — |
| 1.21 | Fabric | 0.102.0+1.21 |
| 1.21.1 | Fabric | 0.115.6+1.21.1 |
| 1.21–1.21.1 | NeoForge | — |
| 1.21.2–1.21.3 | Fabric | 0.106.1+1.21.2 |
| 1.21.2–1.21.3 | NeoForge | — |
| 1.21.4 | Fabric / NeoForge | 0.114.0+1.21.4 |
| 1.21.5 | Fabric / NeoForge | 0.127.1+1.21.5 |
| 1.21.6–1.21.8 | Fabric | 0.128.1+1.21.6 |
| 1.21.6–1.21.8 | NeoForge | — |
| 1.21.9–1.21.10 | Fabric | 0.134.1+1.21.9 |
| 1.21.9–1.21.10 | NeoForge | — |
| 1.21.11 | Fabric / NeoForge | 0.141.2+1.21.11 |
| 26.1.2 | Fabric / NeoForge | 0.146.1+26.1.2 |
| 26.2 | Fabric / NeoForge | 0.158.0+26.2 |
| 26.3 | Fabric / NeoForge (beta) | 0.161.0+26.3 |

Fabric builds need Fabric API at the listed version. NeoForge builds need nothing beyond NeoForge itself.

For the breakdown of which vanilla APIs each band needs and what was rewritten per band, see [`docs/ARCHITECTURE.md` § Per-band specifics](docs/ARCHITECTURE.md#per-band-specifics).

## Installation

1. Drop the matching JAR into your server's `mods/` folder — mind the `-fabric` / `-neoforge` suffix.
2. On Fabric, drop Fabric API for that Minecraft version into the same folder. NeoForge needs no extra dependency.
3. Restart the server.

That's it. Clients connect with an unmodified game (no SlashLoot in their mods folder).

**Singleplayer and LAN.** A singleplayer world runs its own built-in server, so for singleplayer, LAN
or e4mc worlds put SlashLoot (and Fabric API, on Fabric) in your own game's `mods/` folder. Friends who
join your LAN world don't need it. `/slashloot` commands need cheats enabled.

## How it works (one paragraph)

Two intercepts on the vanilla loot path:

1. **Mixin on `RandomizableContainer#unpackLootTable`** (`RandomizableContainerBlockEntity` on Band A; `ContainerEntity#unpackChestVehicleLootTable` for entity containers on Bands B–E): cancel the vanilla "lazy bake loot into the world container" pass. The chest's `LootTable` and `LootTableSeed` NBT tags stay intact forever — from the world's perspective, the chest is permanently "not yet rolled."
2. **A loader event on player right-click** (Fabric `UseBlockCallback`/`UseEntityCallback`): when a player right-clicks a container that still has a `LootTable` tag, look up (or roll fresh) a per-player `SimpleContainer` from a per-dimension `SavedData`, and `player.openMenu(...)` a vanilla `ChestMenu` / `ShulkerBoxMenu` / `HopperMenu` backed by that personal container. Vanilla client sees a normal chest UI synced against personal items.

Per-player seed: `containerSeed XOR player.uuid.msb XOR rotL(player.uuid.lsb, 17)` — same player on same chest gives stable loot.

Persistence lives at `world/<dimension>/data/slashloot.dat`. Full sequence diagram, identity scheme, and per-band file map: [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).

## Configuration

`config/slashloot.json` (created on first launch):

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

- `enabled`: master switch. `false` makes SlashLoot inert — every container behaves like vanilla.
- `dimensionBlocklist`: dimensions (e.g. `"minecraft:the_nether"`) served as ordinary shared vanilla loot.
- `lootTableBlocklist`: loot tables (e.g. `"minecraft:chests/buried_treasure"`) to skip — useful for adventure maps that intentionally use shared loot.
- `handleUnknownContainers`: instance modded containers SlashLoot does not specifically recognise. Off by default; leaving them to vanilla is the compatible choice.
- `delegateContainerAnimation`: forward open/close to the real container so lids animate, barrels open, and trapped chests emit redstone. Leave on unless a mod conflicts.
- `playOpenCloseSounds`: manual open sound. Only used when `delegateContainerAnimation` is `false` (with delegation on, vanilla plays it).
- `cleanupOnBreak`: drop a container's stored loot when it is destroyed.
- `pruneIntervalTicks`: how often the background sweep looks for containers that no longer exist (`0` disables). `pruneBatchSize` caps how many entries it checks per tick.
- `debugLogging`: log one line per container with its position, loot table, and whether SlashLoot instanced it or fell back to vanilla — plus the reason. Repeat verdicts are deduplicated.

Anything missing from the file takes its default, so a config written by an older version keeps working. `/slashloot reload` re-reads it without a restart.

## Admin commands

All require permission level 2 (op).

| Command                                  | Effect                                                                  |
| ---------------------------------------- | ----------------------------------------------------------------------- |
| `/slashloot forget here`                | Wipe every player's personal loot at the block you're looking at        |
| `/slashloot forget at <x> <y> <z>`      | Same, by coordinate                                                     |
| `/slashloot forget player <player>`     | Wipe a player's personal loot at every container in the current dim     |
| `/slashloot forget all`                 | Wipe every stored container in the current dimension                    |
| `/slashloot prune`                      | Drop stored entries whose container no longer exists (loaded chunks)    |
| `/slashloot stats`                      | Stored entry counts for the current dimension                           |
| `/slashloot reload`                     | Re-read `config/slashloot.json`                                        |

## Known limitations

- **Hoppers don't pull from naturally-generated chests.** Since vanilla never bakes loot into the chest, hoppers attached to one see an empty container and extract nothing. Player-placed chests are unaffected — and so are containers on a blocklist or ones SlashLoot does not handle, which behave exactly as in vanilla.
- **Comparator output is always 0** for those same containers, for the same reason. Most map-makers won't care; flag for redstone-heavy worlds.
- **The background prune skips unloaded chunks.** A container destroyed in a chunk that never loads again keeps its (small) stored entry until a later pass finds the chunk loaded.
- **Decorated pots and suspicious sand/gravel** are not covered. They use a different open mechanic (brushing) — would need separate hooks.
- **No looted-state visual indicator.** A color/texture change to mark already-opened containers would require a client mod. Out of scope for v1.

Rationale for each limitation lives in [`docs/ARCHITECTURE.md` § Limitations and rationale](docs/ARCHITECTURE.md#limitations-and-rationale).

## Building from source

Requires JDK 21 (and JDK 17 if you want to build the 1.20.1 band — Loom will fetch a toolchain automatically if needed). The build scripts target Prism Launcher's bundled JDK by default; adjust `JAVA_HOME` if you have your own.

```bash
JAVA_HOME=/path/to/jdk-21 ./gradlew buildAll
# 10 JARs land in build/release/
```

Single band:

```bash
./gradlew :versions:1.21.1-fabric:build
# JAR in versions/1.21.1-fabric/build/libs/
```

To add a new band, see [`docs/ARCHITECTURE.md` § Adding a new band](docs/ARCHITECTURE.md#adding-a-new-band).

## License

[Creative Commons Attribution 4.0 International (CC-BY-4.0)](https://creativecommons.org/licenses/by/4.0/). See `LICENSE`.

# SlashLoot

**Per-player loot for naturally-generated chests, barrels, shulker boxes, and minecarts — server-side only, no client install required.**

Every player who opens a naturally-generated container gets their own personal copy of the loot. Open a mineshaft chest and get your roll; your friend opens that same chest and gets a separate roll of their own. No more racing to dungeons, and nobody arrives to find an empty chest after someone else already cleared it out.

Containers you place yourself are never touched — chests, barrels, and shulker boxes you build behave exactly like vanilla.

**Fabric and NeoForge.**

## Server-side only — players install nothing

SlashLoot runs entirely on the server. Players connect with plain vanilla Fabric or NeoForge: they don't install the mod, and they don't have to update when you do. SlashLoot never replaces the block or its block-state, so the container always stays a real `minecraft:chest` (or barrel, shulker, minecart, etc.). Datapack predicates, `/data` queries, scoreboard selectors, and structure saves keep seeing ordinary vanilla blocks.

## Features

- **Per-player instanced loot** for naturally-generated containers. One open = one personal roll.
- **Persists across server restarts.** Each player's personal loot is saved per-dimension.
- **Stable on re-open.** Close the menu with items still inside and they're there next time you open it.
- **Player-placed containers untouched** — full vanilla behavior for anything you build.
- **Double-chest support.** Each half tracks per-player loot independently; opening either half shows a combined 54-slot view.
- **Vanilla lids, sounds and redstone.** Chest and shulker lids animate, barrels open, and trapped chests still emit redstone.
- **Vanilla-faithful in the small things too.** Locked chests/barrels/shulkers stay locked, a shulker box blocked by a block in front of it stays closed, renamed containers keep their name in the personal menu, opening a container advances the vanilla loot advancement and stats, and nearby piglins still get angry at a guarded container — all exactly like vanilla, even though every open is running through SlashLoot's own menu.
- **Menus close when they should.** Walking away, or the real container being destroyed while you're looking at your copy of it, closes the menu — matching vanilla distance/validity behavior.
- **Configurable.** Per-dimension and per-loot-table blocklists for adventure-map compatibility — blocklisted containers fall back to ordinary vanilla shared loot.
- **Plays nice with other mods.** Cobblemon's Gilded Chests (all seven real color variants) are recognized out of the box as real per-player containers; any other unrecognized modded container can still be opted in broadly, and optional debug logging tells you exactly what SlashLoot decided for each one.
- **Self-maintaining.** Stored loot is cleaned up when a container is destroyed, and the background pruning pass only ever removes an entry once its container is actually gone — never just because a config or blocklist changed.
- **Admin commands** to inspect and wipe personal loot when you need to.

## Container coverage

- Chests, trapped chests, barrels, shulker boxes
- Chest minecarts, hopper minecarts, chest boats
- Double chests (combined 54-slot view)
- Cobblemon Gilded Chests (all real color variants — the Gimmighoul mimic chest is deliberately left untouched)

## Installation

1. Drop the JAR matching your Minecraft version **and loader** into your server's `mods/` folder.
2. On Fabric, drop the matching Fabric API version into the same folder. NeoForge needs nothing extra.
3. Restart the server.

That's it. Clients connect with an unmodified game — nothing to install on their end.

## Supported versions

24 JARs — 13 Fabric, 11 NeoForge. Pick the one matching your server's Minecraft version **and loader** — files are named `slashlootr-<version>+mc<band>-<loader>.jar`. Fabric builds need Fabric API at the listed version; NeoForge builds need nothing beyond NeoForge.

NeoForge coverage differs slightly: NeoForge has no 1.20.1 or 1.20.5 line, and no stable 21.6 / 21.7 / 21.9 builds, so MC 1.21.6–1.21.8 and 1.21.9–1.21.10 are covered by the 21.8 and 21.10 builds. The 26.1.2, 26.2 and 26.3 bands ship for both loaders. The 26.3 NeoForge build is published as **beta** because NeoForge 26.3 itself has no stable release yet.

| Minecraft | Loader | Fabric API (Fabric only) |
| --------- | ------ | ------------------------ |
| 1.20.1 | Fabric | 0.92.2+1.20.1 |
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

## Configuration

A `config/slashlootr.json` file is created on first launch:

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

- **`enabled`** — master switch. `false` makes SlashLoot inert; every container behaves like vanilla.
- **`dimensionBlocklist`** — dimensions (e.g. `"minecraft:the_nether"`) served as ordinary shared vanilla loot.
- **`lootTableBlocklist`** — loot tables (e.g. `"minecraft:chests/buried_treasure"`) to skip. Useful for adventure maps with intentionally shared loot.
- **`handleUnknownContainers`** — instance modded containers SlashLoot doesn't specifically recognise. Off by default: leaving unknown modded containers to vanilla is the compatible choice. (Cobblemon's real Gilded Chests are recognized either way — this only affects containers with no built-in recognition.)
- **`delegateContainerAnimation`** — forward open/close to the real container so lids animate, barrels open, and trapped chests emit redstone. Leave on unless a mod conflicts.
- **`playOpenCloseSounds`** — manual open sound. Only used when `delegateContainerAnimation` is `false` (with delegation on, vanilla plays it).
- **`cleanupOnBreak`** — drop a container's stored loot when it's destroyed.
- **`pruneIntervalTicks`** / **`pruneBatchSize`** — background sweep that reclaims entries for containers that no longer exist. `0` disables it.
- **`debugLogging`** — log one line per container with its position, loot table, and whether SlashLoot instanced it or fell back to vanilla, with the reason. Built for modpack compatibility testing.

Missing keys take their defaults, so a config from an older version keeps working. `/slashloot reload` re-reads the file without a restart.

## Admin commands

All require permission level 2 (op).

| Command                                  | Effect                                                              |
| ---------------------------------------- | ------------------------------------------------------------------ |
| `/slashloot forget here`                | Wipe every player's personal loot at the block you're looking at   |
| `/slashloot forget at <x> <y> <z>`      | Same, by coordinate                                                |
| `/slashloot forget player <player>`     | Wipe a player's personal loot at every container in the current dim |
| `/slashloot forget all`                 | Wipe every stored container in the current dimension               |
| `/slashloot prune`                      | Drop stored entries whose container no longer exists               |
| `/slashloot stats`                      | Stored entry counts for the current dimension                      |
| `/slashloot reload`                     | Re-read `config/slashlootr.json`                                   |

## Known limitations

- **Hoppers don't pull from instanced containers.** The world container is left "unrolled" by design, so a hopper attached to one sees an empty container. Player-placed containers are unaffected — and so are blocklisted ones, which behave exactly as in vanilla.
- **Comparator output reads 0** from those same containers, for the same reason.
- **Decorated pots and suspicious sand/gravel** are not covered — they use a different (brushing) mechanic.
- **The Cobblemon Gimmighoul mimic chest is intentionally not a lootable container** — it's a Pokémon in disguise, not real storage, so SlashLoot leaves it entirely alone.

## Source & links

- **GitHub** (source, issues, architecture deep-dive): https://github.com/slashdaemon/SlashLootr
- **License**: CC-BY-4.0

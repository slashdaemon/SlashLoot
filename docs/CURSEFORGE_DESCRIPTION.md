# SlashLoot

**Per-player loot for naturally-generated chests, barrels, shulker boxes, and minecarts — server-side only, no client install required.**

Every player who opens a naturally-generated container gets their own personal copy of the loot. Open a mineshaft chest and get your roll; your friend opens that same chest and gets a separate roll of their own. No more racing to dungeons, and nobody arrives to find an empty chest after someone else already cleared it out.

Containers you place yourself are never touched — chests, barrels, and shulker boxes you build behave exactly like vanilla.

**Fabric and NeoForge.**

## Server-side only — players install nothing

SlashLoot runs entirely on the server. Players connect with plain vanilla Fabric or NeoForge: they don't install the mod, and they don't have to update when you do. SlashLoot never replaces the block or its block-state, so the container always stays a real `minecraft:chest` (or barrel, shulker, minecart, etc.). Datapack predicates, `/data` queries, scoreboard selectors, and structure saves keep seeing ordinary vanilla blocks.

## Features

- **Per-player instanced loot** for naturally-generated containers. One open = one personal roll.
- **Persists across server restart.** Each player's personal loot is saved per-dimension.
- **Same loot if you re-open.** Close your menu with items still inside? They're still there next time.
- **Player-placed containers are unaffected** — vanilla behavior preserved for chests you built.
- **Double-chest support.** Each half tracks per-player loot independently; opening either half shows a combined 54-slot view.
- **Vanilla lids, sounds and redstone.** Chest and shulker lids animate, barrels open, and trapped chests still emit redstone.
- **Vanilla-faithful in the small things too.** Locked containers stay locked, a blocked shulker box stays closed, renamed containers keep their name, opening a container still advances the vanilla loot advancement and stats, and nearby piglins still get angry at a guarded container.
- **Menus close when they should** — walking away, or the real container being destroyed mid-session, closes your personal menu just like vanilla.
- **Configurable.** Per-dimension and per-loot-table blocklists for adventure-map compatibility.
- **Plays nice with other mods.** Cobblemon's Gilded Chests are recognized out of the box; any other unrecognized modded container can be opted in broadly, with optional debug logging to see exactly what SlashLoot decided.
- **Admin commands.** `/slashloot forget here|at|player` to wipe personal loot when needed.

## Container coverage

- Chests, trapped chests, barrels, shulker boxes
- Chest minecarts, hopper minecarts, chest boats
- Double chests (54-slot combined view)
- Cobblemon Gilded Chests (all real color variants — the Gimmighoul mimic chest is left untouched on purpose)

## Installation

1. Drop the JAR matching your Minecraft version **and loader** into your server's `mods/` folder.
2. On Fabric, drop the matching Fabric API version into the same folder. NeoForge needs nothing extra.
3. Restart the server.

That's it. Clients connect with vanilla Fabric or NeoForge — nothing to install on their end.

## Supported versions

24 JARs — 13 Fabric, 11 NeoForge — pick the one that matches your server's Minecraft version and loader.

NeoForge coverage differs slightly: no 1.20.1 or 1.20.5 line, and no stable 21.6 / 21.7 / 21.9 builds, so MC 1.21.6-1.21.8 and 1.21.9-1.21.10 are covered by the 21.8 and 21.10 builds. The 26.1.2, 26.2 and 26.3 bands ship for both loaders. The 26.3 NeoForge build is published as **beta** because NeoForge 26.3 itself has no stable release yet.

| Minecraft | Fabric API |
|---|---|
| 1.20.1 | 0.92.2+1.20.1 |
| 1.20.5 | 0.97.8+1.20.5 |
| 1.21 | 0.102.0+1.21 |
| 1.21.1 | 0.115.6+1.21.1 |
| 1.21.2 | 0.106.1+1.21.2 |
| 1.21.4 | 0.114.0+1.21.4 |
| 1.21.5 | 0.127.1+1.21.5 |
| 1.21.6 | 0.128.1+1.21.6 |
| 1.21.9 | 0.134.1+1.21.9 |
| 1.21.11 | 0.141.2+1.21.11 |
| 26.1.2 | 0.146.1+26.1.2 |
| 26.2 | 0.158.0+26.2 |
| 26.3 | 0.161.0+26.3 |

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
- **`handleUnknownContainers`** — instance modded containers SlashLoot doesn't specifically recognise. Off by default. Cobblemon's real Gilded Chests are recognized regardless of this setting.
- **`delegateContainerAnimation`** — forward open/close to the real container so lids animate, barrels open, and trapped chests emit redstone.
- **`playOpenCloseSounds`** — manual open sound, used only when `delegateContainerAnimation` is `false`.
- **`cleanupOnBreak`** — drop a container's stored loot when it's destroyed.
- **`pruneIntervalTicks`** / **`pruneBatchSize`** — background sweep that reclaims entries for containers that no longer exist. `0` disables it.
- **`debugLogging`** — log one line per container with its position, loot table, and SlashLoot's verdict.

Missing keys take their defaults. `/slashloot reload` re-reads the file without a restart.

## Admin commands

All require permission level 2 (op).

| Command | Effect |
|---|---|
| `/slashloot forget here` | Wipe every player's personal loot at the block you're looking at |
| `/slashloot forget at <x> <y> <z>` | Same, by coordinate |
| `/slashloot forget player <player>` | Wipe a player's personal loot at every container in the current dim |
| `/slashloot forget all` | Wipe every stored container in the current dimension |
| `/slashloot prune` | Drop stored entries whose container no longer exists |
| `/slashloot stats` | Stored entry counts for the current dimension |
| `/slashloot reload` | Re-read `config/slashlootr.json` |

## Known limitations

- **Hoppers don't pull from instanced containers.** The world container stays "unrolled" by design, so hoppers see an empty container. Player-placed containers are unaffected — and so are blocklisted ones, which behave exactly as in vanilla.
- **Comparator output reads 0** from those same containers, for the same reason.
- **Decorated pots and suspicious sand/gravel** are not covered — they use a different brushing mechanic.
- **The Cobblemon Gimmighoul mimic chest is intentionally not treated as a lootable container** — it's a Pokémon in disguise, not real storage.

## Source & links

- **GitHub** (source, issues): https://github.com/slashdaemon/SlashLootr
- **Architecture / per-band drift docs**: https://github.com/slashdaemon/SlashLootr/blob/main/docs/ARCHITECTURE.md
- **License**: CC-BY-4.0

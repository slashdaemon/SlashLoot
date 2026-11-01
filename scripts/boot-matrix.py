"""Release gate, Tier 0 and 1: every advertised (jar, MC) pair boots and works headlessly.

    python scripts/boot-matrix.py                 every pair
    python scripts/boot-matrix.py --only 1.21.5   pairs whose band or MC matches

Tier 0: the server reaches Done with the mod loaded and no mixin errors. Compiling proves none of
that: a mixin aimed at a missing method only fails at boot, which is how the 1.20.1 jar shipped
crashing. Chest boats and minecarts are summoned so every mixin target class loads.

Tier 1: the CLAUDE.md RCON sweep. A hopper under a container triggers the same unpack path the
mixins hook, so no client is needed. Results go to <WORK>/results/boot.json.
"""
import argparse
import json
import sys
import time
import traceback

from matrix_common import BLOCKED_TABLE, RESULTS, TABLE, WORK, Server, advertised, chest_boat_id, nbt_loot

Y = -60


def sweep(s, mc):
    """Returns {check: passed}. Fixtures sit on hoppers in the ground, which pull from what is above."""
    r = s.rcon
    r("forceload add -2 -4 40 8")
    place = [
        (0, f"minecraft:chest[facing=south]{nbt_loot(TABLE, 1)}"),
        (5, f"minecraft:chest[facing=south]{nbt_loot(BLOCKED_TABLE, 2)}"),
        (10, f"minecraft:hopper{nbt_loot(TABLE, 3)}"),
        (15, f"minecraft:barrel[facing=up]{nbt_loot(TABLE, 4)}"),
        (20, "minecraft:rail"),
    ]
    for x in (0, 5, 10, 15, 20, 25):
        r(f"setblock {x} {Y - 1} 0 minecraft:hopper[facing=down]")
    for x, block in place:
        r(f"setblock {x} {Y} 0 {block}")
    r(f'summon minecraft:chest_minecart 20.5 {Y} 0.5 {{LootTable:"{TABLE}",LootTableSeed:5L}}')
    r(f'summon {chest_boat_id(mc)} 25.5 {Y} 0.5 {{LootTable:"{TABLE}",LootTableSeed:6L}}')
    time.sleep(3)

    def tag(x):
        out = r(f"data get block {x} {Y} 0 LootTable")
        return "simple_dungeon" in out or "igloo" in out

    def hopper_has_items(x):
        return "id:" in r(f"data get block {x} {Y - 1} 0 Items")

    def entity_tag(kind):
        return "simple_dungeon" in r(f"data get entity @e[type={kind},limit=1] LootTable")

    return {
        "chest_instanced_tag_survives": tag(0),
        "chest_instanced_hopper_empty": not hopper_has_items(0),
        "blocked_table_vanilla_tag_gone": not tag(5),
        "blocked_table_vanilla_hopper_filled": hopper_has_items(5),
        "unsupported_hopper_vanilla_filled": hopper_has_items(10),
        "barrel_instanced_tag_survives": tag(15),
        "barrel_instanced_hopper_empty": not hopper_has_items(15),
        "minecart_instanced_tag_survives": entity_tag("minecraft:chest_minecart"),
        "minecart_instanced_hopper_empty": not hopper_has_items(20),
        "boat_instanced_tag_survives": entity_tag(chest_boat_id(mc)),
        "boat_instanced_hopper_empty": not hopper_has_items(25),
        "stats_command_works": "SlashLoot" in r("slashloot stats"),
    }


def run_pair(band, loader, mc):
    out = {"band": band, "loader": loader, "mc": mc, "boot": False, "checks": {}, "errors": []}
    s = Server(band, loader, mc, config={"dimensionBlocklist": [], "lootTableBlocklist": [BLOCKED_TABLE]})
    try:
        out["boot"] = bool(s.start())
        out["neoforge"] = getattr(s, "nf_version", None)
        if out["boot"]:
            out["checks"] = sweep(s, mc)
        else:
            out["errors"] = s.text().splitlines()[-6:]
        out["errors"] += s.mod_errors()
    except Exception as e:
        out["errors"].append(f"{type(e).__name__}: {e}")
    finally:
        try:
            s.stop()
        except Exception:
            traceback.print_exc()
    mod_errors = [e for e in out["errors"] if "slashloot" in e.lower() or "blockacademy" in e.lower()]
    out["pass"] = out["boot"] and bool(out["checks"]) and all(out["checks"].values()) and not mod_errors
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--only", action="append", default=[])
    args = ap.parse_args()
    pairs = [p for p in advertised() if not args.only or p[0] in args.only or p[2] in args.only]
    RESULTS.mkdir(parents=True, exist_ok=True)
    results = []
    for band, loader, mc in pairs:
        r = run_pair(band, loader, mc)
        results.append(r)
        bad = [k for k, v in r["checks"].items() if not v]
        print(f"{'PASS' if r['pass'] else 'FAIL'}  {loader:8} jar {band:8} on MC {mc:8} nf={r.get('neoforge')} {bad or ''}", flush=True)
        if not r["pass"]:
            for e in r["errors"][-3:]:
                print("      ", e[:200], flush=True)
        (RESULTS / "boot.json").write_text(json.dumps(results, indent=1))
    ok = sum(r["pass"] for r in results)
    print(f"{ok}/{len(results)} pairs pass. Work dir: {WORK}")
    return 0 if ok == len(results) else 1


if __name__ == "__main__":
    sys.exit(main())

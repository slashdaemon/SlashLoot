"""Release gate, Tier 2: every jar on its primary MC version, driven by a real vanilla client.

    python scripts/client-matrix.py                         every jar
    python scripts/client-matrix.py --only 1.21.5           one band, both loaders
    python scripts/client-matrix.py --only 1.21.1 --loader fabric --stages vehicles
    python scripts/client-matrix.py --two-players           two clients share one chest boat
    python scripts/client-matrix.py --cleanup               delete the SL-Vanilla-* instances

Clients are plain vanilla Prism instances, created on demand: SlashLoot needs no client mod, so this
is exactly what a player has. Input comes from LocalServer/scripts/drive.py, which needs the Minecraft
window in the foreground, so the PC cannot be used while this runs.

A quick open-and-close beats vanilla's 5-tick opener recheck, so every open here is held past it.
Screenshots are kept in <WORK>/shots/<band>-<loader>/; results in <WORK>/results/client.json.
"""
import argparse
from collections import Counter
import importlib
import json
import os
import shutil
import subprocess
import sys
import time
from pathlib import Path

from PIL import Image, ImageStat

from matrix_common import (RELEASE, REPO, RESULTS, TABLE, WORK, Server, advertised, chest_boat_id, get, jar_loader,
                           java_for, nbt_loot, primary_mc)

sys.path.insert(0, str(REPO.parent / "LocalServer" / "scripts"))
drive = importlib.import_module("drive")

Y = -60
PLAYER = "PCTester"
PRISM = Path(os.environ["APPDATA"]) / "PrismLauncher" / "instances"
OPTIONS = """onboardAccessibility:false
joinedFirstServer:true
skipMultiplayerWarning:true
tutorialStep:none
pauseOnLostFocus:false
showSubtitles:true
guiScale:2
fullscreen:false
soundCategory_master:0.1
"""


def instance_for(mc, suffix=""):
    name = f"SL-Vanilla-{mc}{suffix}"
    home = PRISM / name
    if home.exists():
        return name
    (home / "minecraft").mkdir(parents=True)
    java = str(java_for(mc)).replace("java.exe", "javaw.exe").replace("\\", "/")
    (home / "instance.cfg").write_text(
        "[General]\nConfigVersion=1.3\nInstanceType=OneSix\n"
        f"name={name}\nJoinServerOnLaunch=true\nJoinServerOnLaunchAddress=localhost:25565\n"
        f"AutomaticJava=true\nOverrideJavaLocation=true\nJavaPath={java}\n")
    # Prism cannot resolve a bare net.minecraft component on first launch: it needs LWJGL listed too.
    meta = json.loads(get(f"https://meta.prismlauncher.org/v1/net.minecraft/{mc}.json"))
    lwjgl = next(x for x in meta["requires"] if x["uid"].startswith("org.lwjgl"))
    (home / "mmc-pack.json").write_text(json.dumps({"formatVersion": 1, "components": [
        {"uid": lwjgl["uid"], "version": lwjgl["suggests"], "dependencyOnly": True},
        {"uid": "net.minecraft", "version": mc, "important": True, "cachedRequires": [lwjgl]},
    ]}))
    (home / "minecraft" / "options.txt").write_text(OPTIONS)
    return name


def slots_box(img, rows=3):
    """The chest-menu slot grid at guiScale 2."""
    w, h = img.size
    gui_h = (114 + rows * 18) * 2
    left, top = (w - 352) // 2, (h - gui_h) // 2
    return (left + 16, top + 36, left + 16 + 324, top + 36 + rows * 36)


def _flat_greys(img, box, floor=100):
    """Neutral greys in box, at or above floor, most common first."""
    return Counter(p for p in img.crop(box).get_flattened_data() if p[0] == p[1] == p[2] and p[0] >= floor)


def menu_open(path):
    """True when a container screen is showing: a large flat grey panel (vanilla 198) plus large flat
    grey slots (vanilla 139). Matched by their ratio, not exact values, because some GPU drivers
    brighten the game's output (one laptop renders them as 255 and 213)."""
    img = Image.open(path).convert("RGB")
    w, h = img.size
    box = (w // 2 - 176, 0, w // 2 + 176, h)
    n = (box[2] - box[0]) * h
    greys = [p[0] for p, k in _flat_greys(img, box).most_common(4) if k > n * 0.02]
    return any(b < a and 0.62 <= b / a <= 0.9 for a in greys for b in greys)


def has_items(path):
    """True when a menu is showing and its slot grid holds anything but the empty-slot colours."""
    if not menu_open(path):
        return False
    img = Image.open(path).convert("RGB")
    px = list(img.crop(slots_box(img)).get_flattened_data())
    # The grid's own flat greys (slot, border, highlight), whatever brightness the driver applied.
    plain = {p for p, _ in Counter(px).most_common(4) if p[0] == p[1] == p[2]}
    return sum(p not in plain for p in px) > len(px) * 0.01


def count_pixels(img, box):
    """Near-white pixels in a slot's bottom-right, where the stack count is drawn."""
    left, top, right, bottom = box
    return sum(min(p) > 235 for p in img.crop((left + 10, top + 16, right, bottom)).get_flattened_data())


def _item_mask(img, box):
    """Which pixels of a slot are item rather than the grid's own flat greys."""
    plain = {p for p, _ in Counter(img.crop(slots_box(img)).get_flattened_data()).most_common(4)
             if p[0] == p[1] == p[2]}
    return [p not in plain for p in img.crop(box).get_flattened_data()]


def same_slots(a, b):
    """Slot by slot: the item's silhouette, because the animated enchantment glint changes an item's
    colours (enough to move its average past any useful tolerance) but never its shape, and the count
    corner separately, because a digit barely moves the silhouette. Same loot differs by at most ~13
    pixels per slot; a different item by hundreds."""
    if not (menu_open(a) and menu_open(b)):
        return False
    ia, ib = Image.open(a).convert("RGB"), Image.open(b).convert("RGB")
    left, top, _, bottom = slots_box(ia)
    for row in range((bottom - top) // 36):
        for col in range(9):
            box = (left + col * 36, top + row * 36, left + col * 36 + 36, top + row * 36 + 36)
            if sum(x != y for x, y in zip(_item_mask(ia, box), _item_mask(ib, box))) > 40:
                return False
            if abs(count_pixels(ia, box) - count_pixels(ib, box)) > 6:
                return False
    return True


class Client:
    def __init__(self, mc, shots, player=PLAYER, suffix=""):
        self.inst = instance_for(mc, suffix)
        self.player = player
        self.shots = shots
        self.stalls = 0
        self.mc = mc
        self.download()

    def download(self):
        """--offline never downloads, so a version Prism has not cached stalls with no visible error.
        One online launch fetches it. Runs before the test server is up, so nothing joins."""
        jar = PRISM.parent / "libraries" / "com" / "mojang" / "minecraft" / self.mc / f"minecraft-{self.mc}-client.jar"
        if jar.exists():
            return
        subprocess.run(["taskkill", "/F", "/IM", "prismlauncher.exe"], capture_output=True)
        time.sleep(2)
        subprocess.Popen([drive.PRISM, "--launch", self.inst], creationflags=0x8)
        pat = drive.inst_pat(self.inst)
        end = time.time() + 600
        while time.time() < end:
            time.sleep(3)
            if drive.ps(f"$h = Get-MCWin '{pat}'; if ($h -ne [IntPtr]::Zero) {{ 'UP' }}") == "UP":
                self.stop()
                time.sleep(3)
                return
        raise RuntimeError(f"Prism could not download MC {self.mc}")

    def launch(self):
        """Prism aborts the first launch of a new instance while it fetches metadata, so retry."""
        pat = drive.inst_pat(self.inst)
        for _ in range(4):
            subprocess.run(["taskkill", "/F", "/IM", "prismlauncher.exe"], capture_output=True)
            time.sleep(2)
            subprocess.Popen([drive.PRISM, "--launch", self.inst, "--offline", self.player], creationflags=0x8)
            end = time.time() + 120
            while time.time() < end:
                time.sleep(3)
                if drive.ps(f"$h = Get-MCWin '{pat}'; if ($h -ne [IntPtr]::Zero) {{ 'UP' }}") == "UP":
                    x, y, w, h = drive.WIN_RECT
                    drive.ps(f"$h = Get-MCWin '{pat}'; [SSK]::ShowWindow($h, 9) | Out-Null; "
                             f"[SSK]::SetWindowPos($h, [IntPtr]::Zero, {x}, {y}, {w}, {h}, 0x0040) | Out-Null")
                    return True
            # Prism rotates its logs on every start, so keep the evidence of each stalled launch.
            self.stalls += 1
            log = PRISM.parent / "logs" / "PrismLauncher-0.log"
            if log.exists():
                shutil.copy(log, self.shots / f"prism-stall-{self.stalls}.log")
        return False

    def stop(self):
        drive.main(["--instance", self.inst, "stop"])

    def click(self):
        return drive.with_window(self.inst, ["[SSK]::mouse_event(8, 0, 0, 0, [UIntPtr]::Zero)", "Start-Sleep -Milliseconds 100",
                                             "[SSK]::mouse_event(16, 0, 0, 0, [UIntPtr]::Zero)"])

    def sneak_click(self):
        return drive.with_window(self.inst, [f"Key {drive.VK['lshift']} $false $false", "Start-Sleep -Milliseconds 300",
                                             "[SSK]::mouse_event(8, 0, 0, 0, [UIntPtr]::Zero)", "Start-Sleep -Milliseconds 100",
                                             "[SSK]::mouse_event(16, 0, 0, 0, [UIntPtr]::Zero)", "Start-Sleep -Milliseconds 100",
                                             f"Key {drive.VK['lshift']} $true $false"])

    def key(self, *keys):
        return drive.with_window(self.inst, drive.key_lines(list(keys)))

    def snap(self, name):
        path = self.shots / f"{name}.png"
        drive.with_window(self.inst, ["$c = Get-Client $h", "Add-Type -AssemblyName System.Drawing",
                                      "$bmp = New-Object System.Drawing.Bitmap $c.W, $c.H",
                                      "[System.Drawing.Graphics]::FromImage($bmp).CopyFromScreen($c.L, $c.T, 0, 0, (New-Object System.Drawing.Size $c.W, $c.H))",
                                      f"$bmp.Save('{path}', [System.Drawing.Imaging.ImageFormat]::Png)"])
        return path


def fixtures(r, mc):
    r("forceload add -2 -4 48 8")
    r("time set noon")
    lamp = "minecraft:redstone_lamp"
    blocks = [
        (0, f"minecraft:chest[facing=south]{nbt_loot(TABLE, 1)}"),
        (5, f"minecraft:trapped_chest[facing=south]{nbt_loot(TABLE, 2)}"), (6, lamp),
        (10, f"minecraft:barrel[facing=up]{nbt_loot(TABLE, 3)}"),
        (15, f"minecraft:shulker_box[facing=up]{nbt_loot(TABLE, 4)}"),
        (20, f"minecraft:chest[facing=south,type=right]{nbt_loot(TABLE, 5)}"),
        (21, f"minecraft:chest[facing=south,type=left]{nbt_loot(TABLE, 6)}"),
        (24, lamp),
        (25, f"minecraft:trapped_chest[facing=south,type=right]{nbt_loot(TABLE, 7)}"),
        (26, f"minecraft:trapped_chest[facing=south,type=left]{nbt_loot(TABLE, 8)}"),
        (27, lamp), (30, "minecraft:rail"), (35, "minecraft:rail"),
    ]
    for x, block in blocks:
        r(f"setblock {x} {Y} 0 {block}")
    r(f'summon minecraft:chest_minecart 30.5 {Y} 0.5 {{LootTable:"{TABLE}",LootTableSeed:9L}}')
    r(f'summon minecraft:hopper_minecart 35.5 {Y} 0.5 {{LootTable:"{TABLE}",LootTableSeed:10L}}')
    r(f'summon {chest_boat_id(mc)} 40.5 {Y} 0.5 {{LootTable:"{TABLE}",LootTableSeed:11L}}')


def aim(r, x, pitch=33, player=PLAYER):
    r(f"tp {player} {x} {Y} 2.5 180 {pitch}")
    time.sleep(0.7)


def passed(r, probe):
    return "passed" in r(f"execute if block {probe}")


def cycles(r, c, x, probes, n=3):
    """Open held past the recheck, close, n times. Every state must hold, on every open."""
    aim(r, x)
    bad = []
    for i in range(1, n + 1):
        c.click()
        time.sleep(0.9)
        for p in probes:
            if not passed(r, p):
                bad.append(f"open{i}:{p.split()[-1]}")
        c.key("esc")
        time.sleep(0.6)
        for p in probes:
            if passed(r, p):
                bad.append(f"close{i}:{p.split()[-1]}")
    return bad


def stage_cycles(r, c, out):
    lamp6 = f"6 {Y} 0 minecraft:redstone_lamp[lit=true]"
    out["trapped"] = cycles(r, c, 5.5, [lamp6])
    out["barrel"] = cycles(r, c, 10.5, [f"10 {Y} 0 minecraft:barrel[open=true]"])
    out["double_trapped"] = cycles(r, c, 26.0, [f"24 {Y} 0 minecraft:redstone_lamp[lit=true]",
                                                f"27 {Y} 0 minecraft:redstone_lamp[lit=true]"])
    for name, x in (("chest", 0.5), ("double", 21.0), ("shulker", 15.5)):
        aim(r, x)
        for i in (1, 2):
            c.click()
            time.sleep(0.5)
            c.snap(f"{name}-open{i}")
            time.sleep(0.5)
            c.key("esc")
            time.sleep(1.2)


def open_vehicle(r, c, x, shot, opener):
    aim(r, x)
    opener()
    time.sleep(0.8)
    path = c.snap(shot)
    return path


def riding(r):
    return "following" in r(f"execute as {PLAYER} on vehicle run data get entity @s UUID")


def stage_vehicles(r, c, out, mc):
    boat = chest_boat_id(mc)
    for name, x, kind in (("minecart", 30.5, "minecraft:chest_minecart"), ("hopper_minecart", 35.5, "minecraft:hopper_minecart")):
        before = r("slashloot stats")
        path = open_vehicle(r, c, x, name, c.click)
        c.key("esc")
        time.sleep(0.5)
        # A dropped key leaves this menu up, and the next vehicle's click lands inside it.
        if menu_open(c.snap("menu-closed")):
            out.setdefault("reclosed", []).append(name)
            c.key("esc")
            time.sleep(0.5)
        out[name] = {"copy_made": before != r("slashloot stats"),
                     "tag": "simple_dungeon" in r(f"data get entity @e[type={kind},limit=1] LootTable"),
                     "shot": path.name}

    # Plain click boards, as in vanilla.
    open_vehicle(r, c, 40.5, "boat-plain", c.click)
    boarded = riding(r)
    if not boarded:
        # Vanilla opens a full boat instead of boarding it, and boats pick up mobs that walk into them.
        out["boat_passengers"] = r(f"data get entity @e[type={boat},limit=1] Passengers")
    c.key("esc") if not boarded else None
    r(f"ride {PLAYER} dismount")
    time.sleep(0.5)

    # Sneak-click opens.
    path = open_vehicle(r, c, 40.5, "boat-sneak", c.sneak_click)
    sneak_items = has_items(path)
    c.key("esc")
    time.sleep(0.5)

    # Riding, the inventory key opens the chest. The same copy, so the same slots.
    r(f"ride {PLAYER} mount @e[type={boat},limit=1]")
    time.sleep(0.8)
    c.key("e")
    time.sleep(0.8)
    path2 = c.snap("boat-rider-e")
    rider_items = has_items(path2)
    same = same_slots(path, path2)
    c.key("esc")
    time.sleep(0.5)
    r(f"ride {PLAYER} dismount")
    out["boat"] = {"plain_boards": boarded, "sneak_has_items": sneak_items, "rider_e_has_items": rider_items,
                   "rider_same_copy": same,
                   "tag": "simple_dungeon" in r(f"data get entity @e[type={boat},limit=1] LootTable")}


def open_and_snap(r, c, x, shot, out):
    """A missed click is the harness's fault, not the mod's: re-aim once and record that it happened."""
    for attempt in (1, 2):
        aim(r, x)
        c.click()
        time.sleep(0.6)
        path = c.snap(shot)
        if menu_open(path):
            return path
        out.setdefault("reaimed", []).append(shot)
        time.sleep(1)
    return path


def stage_persist(s, c, out):
    r = s.rcon
    shots = {}
    for name, x in (("chest", 0.5), ("barrel", 10.5)):
        shots[name] = open_and_snap(r, c, x, f"persist-{name}-before", out)
        c.key("esc")
        time.sleep(0.5)
    c.stop()
    r("save-all flush")
    s.stop()
    if not s.start():
        out["persist"] = {"restart": False}
        return
    if not join(s, c, out):
        out["persist"] = {"rejoin": False}
        return
    r = s.rcon
    res = {}
    for name, x in (("chest", 0.5), ("barrel", 10.5)):
        after = open_and_snap(r, c, x, f"persist-{name}-after", out)
        res[name] = same_slots(shots[name], after)
        c.key("esc")
        time.sleep(0.5)
    res["tag"] = "simple_dungeon" in r(f"data get block 0 {Y} 0 LootTable")
    out["persist"] = res


def stuck_connecting(path):
    """The dirt connect screen: 1.20.1 clients can hang here after the server has logged the join."""
    px = list(Image.open(path).convert("RGB").crop((0, 0, 400, 30)).get_flattened_data())
    return sum(p[0] for p in px) > sum(p[2] for p in px) * 1.2


def join(s, c, out=None):
    """A vanilla client that hangs while connecting says nothing about the mod, so relaunch once and record it."""
    for attempt in (1, 2):
        if attempt == 2:
            c.stop()
            time.sleep(3)
            if out is not None:
                out.setdefault("rejoined", []).append(c.player)
        mark = len(s.text())
        if not c.launch():
            continue
        end = time.time() + 120
        while time.time() < end and f"{c.player} joined" not in s.text()[mark:]:
            time.sleep(1)
        if f"{c.player} joined" not in s.text()[mark:]:
            continue
        for _ in range(12):
            time.sleep(3)
            if not stuck_connecting(c.snap("join")):
                time.sleep(2)
                return True
    return False


def problems(out):
    bad = [f"{k}:{v}" for k, v in out.items() if k in ("trapped", "barrel", "double_trapped") and v]
    for group in ("minecart", "hopper_minecart", "boat", "persist"):
        for k, v in out.get(group, {}).items():
            if k != "shot" and not v:
                bad.append(f"{group}.{k}")
    return bad + out.get("errors", [])


def run_jar(band, loader, stages, jar):
    mc = primary_mc(band, loader)
    shots = WORK / "shots" / f"{band}-{loader}"
    shots.mkdir(parents=True, exist_ok=True)
    out = {"band": band, "loader": loader, "mc": mc}
    level = f"world-client-{band}-{loader}".replace(".", "_")
    shutil.rmtree(WORK / "fabric" / level, ignore_errors=True)
    s = Server(band, loader, mc, jar=jar, level=level)
    c = Client(mc, shots)
    try:
        if not s.start():
            out["errors"] = ["server did not start"] + s.text().splitlines()[-3:]
            return out
        out["neoforge"] = getattr(s, "nf_version", None)
        fixtures(s.rcon, mc)
        if not join(s, c, out):
            out["errors"] = ["client did not join"] + s.text().splitlines()[-3:]
            return out
        if "cycles" in stages:
            stage_cycles(s.rcon, c, out)
        if "vehicles" in stages:
            stage_vehicles(s.rcon, c, out, mc)
        if "persist" in stages:
            stage_persist(s, c, out)
        out["errors"] = s.mod_errors()
    except Exception as e:
        out["errors"] = [f"{type(e).__name__}: {e}"]
    finally:
        c.stop()
        s.stop()
    return out


def migrate(loader, source, target):
    """Personal loot written by `source` must survive a server restart onto `target`.

    Each is band:version, for a jar in build/release or <WORK>/old. Loot rolls from a deterministic
    seed, so a lost file re-rolls identical items: the store's block count is what tells them apart.
    """
    def jar_of(spec):
        band, version = spec.split(":")
        # Releases before 0.4.0 were named slashlootr.
        found = [d / f"{name}-{version}+mc{band}-{jar_loader(loader)}.jar"
                 for d in (RELEASE, WORK / "old") for name in ("slashloot", "slashlootr")]
        return band, primary_mc(band, loader), next(p for p in found if p.exists())

    shots = WORK / "shots" / f"migrate-{loader}-{source}-{target}".replace(":", "_")
    shots.mkdir(parents=True, exist_ok=True)
    level = "world-migrate"
    shutil.rmtree(WORK / "fabric" / level, ignore_errors=True)
    band, mc, jar = jar_of(source)
    s, c = Server(band, loader, mc, jar=jar, level=level), Client(mc, shots)
    out = {"source": source, "target": target}
    try:
        s.start()
        s.rcon("forceload add -2 -4 16 8")
        s.rcon(f"setblock 0 {Y} 0 minecraft:chest[facing=south]{nbt_loot(TABLE, 1)}")
        if not join(s, c):
            raise SystemExit(f"client did not join the {source} server")
        before = open_and_snap(s.rcon, c, 0.5, "before", out)
        c.key("esc")
        time.sleep(0.5)
        out["before"] = s.rcon("slashloot stats")
        c.stop()
        s.rcon("save-all flush")
    finally:
        c.stop()
        s.stop()
    band, mc, jar = jar_of(target)
    s, c = Server(band, loader, mc, jar=jar, level=level, fresh=False), Client(mc, shots)
    try:
        s.start()
        out["after"] = s.rcon("slashloot stats")
        out["store_survived"] = "blocks=1" in out["after"]
        if not join(s, c):
            raise SystemExit(f"client did not join the {target} server: {out}")
        after = open_and_snap(s.rcon, c, 0.5, "after", out)
        out["has_items"], out["same_slots"] = has_items(after), same_slots(before, after)
        c.key("esc")
        time.sleep(0.5)
        # The target's own save must then hold still: a legacy read is compacted once, never again.
        c.stop()
        s.rcon("save-all flush")
        s.stop()
        s.start()
        if not join(s, c, out):
            raise SystemExit(f"client did not rejoin the {target} server: {out}")
        again = open_and_snap(s.rcon, c, 0.5, "after-restart", out)
        out["stable_after_restart"] = same_slots(after, again)
        c.key("esc")
        out["store_errors"] = [l[:200] for l in s.text().splitlines() if "slashloot" in l.lower() and "rror" in l][:4]
    finally:
        c.stop()
        s.stop()
    print(json.dumps(out, indent=1), flush=True)
    return 0 if out["store_survived"] and out["has_items"] and out["stable_after_restart"] and not out["store_errors"] else 1


def two_players():
    """An occupied boat opens on a plain click, and two players on it each get their own copy."""
    band, loader = "1.21.1", "fabric"
    mc = primary_mc(band, loader)
    shots = WORK / "shots" / "two-players"
    shots.mkdir(parents=True, exist_ok=True)
    s = Server(band, loader, mc, level="world-two-players")
    a, b = Client(mc, shots), Client(mc, shots, "PCTester2", "-b")
    out = {}
    try:
        s.start()
        fixtures(s.rcon, mc)
        if not (join(s, a) and join(s, b)):
            raise SystemExit("clients did not join")
        r = s.rcon
        boat = chest_boat_id(mc)
        r(f"ride {PLAYER} mount @e[type={boat},limit=1]")
        time.sleep(1)
        a.key("e")
        time.sleep(0.8)
        aim(r, 40.95, player=b.player)  # the hull beside the rider: a click on the rider misses the boat
        b.click()
        time.sleep(0.8)
        shot_a, shot_b = a.snap("rider"), b.snap("second-player")
        out["rider_has_items"], out["plain_click_opens_occupied_boat"] = has_items(shot_a), has_items(shot_b)
        out["separate_copies"] = menu_open(shot_a) and menu_open(shot_b) and not same_slots(shot_a, shot_b)
        out["copies"] = r("slashloot stats")
        out["tag"] = "simple_dungeon" in r(f"data get entity @e[type={boat},limit=1] LootTable")
    finally:
        a.stop()
        b.stop()
        s.stop()
    print(json.dumps(out, indent=1), flush=True)
    return 0 if all(out.values()) and "player copies=2" in out["copies"] else 1


def cleanup():
    for home in PRISM.glob("SL-Vanilla-*"):
        shutil.rmtree(home)
    print("removed SL-Vanilla-* instances")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--only", action="append", default=[])
    ap.add_argument("--loader")
    ap.add_argument("--stages", default="cycles,vehicles,persist")
    ap.add_argument("--jar", help="test this jar instead of build/release, with exactly one band selected")
    ap.add_argument("--migrate", nargs=2, metavar=("BAND:VERSION", "BAND:VERSION"))
    ap.add_argument("--resume", action="store_true", help="keep passes from the last client.json")
    ap.add_argument("--two-players", action="store_true")
    ap.add_argument("--cleanup", action="store_true")
    args = ap.parse_args()
    if args.migrate:
        return migrate(args.loader or "fabric", *args.migrate)
    if args.two_players:
        return two_players()
    if args.cleanup:
        cleanup()
        return 0
    jars = sorted({(b, l) for b, l, _ in advertised()}, key=lambda p: (p[1], p[0]))
    jars = [j for j in jars if (not args.only or j[0] in args.only) and (not args.loader or j[1] == args.loader)]
    RESULTS.mkdir(parents=True, exist_ok=True)
    results = []
    if args.resume and (RESULTS / "client.json").exists():
        results = [r for r in json.loads((RESULTS / "client.json").read_text()) if r.get("pass")]
    done = {(r["band"], r["loader"]) for r in results}
    for band, loader in jars:
        if (band, loader) in done:
            continue
        out = run_jar(band, loader, args.stages.split(","), args.jar)
        bad = problems(out)
        out["pass"] = not bad
        results.append(out)
        print(f"{'PASS' if not bad else 'FAIL'}  {loader:8} jar {band:8} MC {out['mc']:8} {bad or ''}", flush=True)
        (RESULTS / "client.json").write_text(json.dumps(results, indent=1))
    ok = sum(r["pass"] for r in results)
    print(f"{ok}/{len(results)} jars pass")
    return 0 if ok == len(results) else 1


if __name__ == "__main__":
    sys.exit(main())

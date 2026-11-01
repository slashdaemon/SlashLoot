#!/usr/bin/env python3
"""Headless boot + functional smoke test for every SlashLoot band.

Automates CLAUDE.md § Verification "Headless functional pass". For each band it boots the band's
dev server (`runServer`) in a throwaway `smoke-world`, fails on any Mixin/loader/SlashLoot error or
crash report, then places the standard hopper fixtures over RCON and checks each verdict:

    chest over hopper              INSTANCE  - LootTable tag survives
    barrel over hopper             INSTANCE  - LootTable tag survives
    chest minecart over hopper     INSTANCE  - LootTable tag survives
    blocklisted chest over hopper  vanilla   - tag consumed by the vanilla roll
    hopper with a loot table       vanilla   - unsupported container, tag consumed

--migration adds a second boot: the config and save are swapped for their pre-0.4.0 `slashlootr`
names and the band must move them over (`Moved config`, `Migrated`, new save file on disk).

--prod runs the same checks against the SHIPPED jar on a real server built by the loader's official
installer (build/smoke/prod-<target>/). Dev runs are Mojang-named; Forge production is SRG-named, so
a broken refmap or reobf only shows up here. Targets: `forge` (Forge 1.20.1) and `neoforge-1.20.1`
(the same jar on NeoForge's 1.20.1 line).

--integrated adds a client pass to Fabric bands: after the dedicated pass, the band's `runClient`
opens `smoke-world` as a singleplayer world (--quickPlaySingleplayer), so SlashLoot has to load in a
client JVM and serve the integrated server. There is no RCON there, so a datapack dropped into the
world runs the same FIXTURES and reports with `say SMOKE ...` lines in the client log. This opens a
real game window per band. With --all it runs the Fabric bands only.

Usage:
    python scripts/smoke.py --all
    python scripts/smoke.py --band 1.20.1-fabric --band 26.3-neoforge --migration
    python scripts/smoke.py --prod forge --prod neoforge-1.20.1
    python scripts/smoke.py --all --integrated

Band names are the composite projects (`1.21.1-fabric`) or `<26.x dir>-<loader>` (`26.3-fabric`).
Uses JAVA_HOME if set, else Prism's java-runtime-delta (JDK 21); the 26.x wrappers fetch JDK 25
themselves. Runs on 25594/25595 (--port to move) so neither LocalServer nor another repo's test
server on 25590/25591 is disturbed. Existing
`world/` saves in each run dir are never touched.

Stdlib only.
"""

import argparse
import gzip
import os
import re
import shutil
import socket
import struct
import subprocess
import sys
import time
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
QUARANTINED = ["26.1.2", "26.2", "26.3"]
WORLD = "smoke-world"
PORT, RCON_PORT, RCON_PASSWORD = 25594, 25595, "slashtest"  # --port overrides; RCON is port + 1
BOOT_TIMEOUT = 420
CLIENT_TIMEOUT = 900  # a band's first runClient downloads its assets
IS_WINDOWS = os.name == "nt"

TABLE = "minecraft:chests/simple_dungeon"
BLOCKED = "minecraft:chests/abandoned_mineshaft"
CONFIG = """{
  "debugLogging": true,
  "lootTableBlocklist": ["%s"],
  "pruneIntervalTicks": 40
}
""" % BLOCKED

# (name, container position, setup commands, instanced?)
FIXTURES = [
    ("chest", (0, 100, 0), [
        "setblock 0 99 0 minecraft:hopper[facing=down]",
        'setblock 0 100 0 minecraft:chest{LootTable:"%s",LootTableSeed:1L}' % TABLE,
    ], True),
    ("barrel", (4, 100, 0), [
        "setblock 4 99 0 minecraft:hopper[facing=down]",
        'setblock 4 100 0 minecraft:barrel{LootTable:"%s",LootTableSeed:1L}' % TABLE,
    ], True),
    ("blocklisted chest", (8, 100, 0), [
        "setblock 8 99 0 minecraft:hopper[facing=down]",
        'setblock 8 100 0 minecraft:chest{LootTable:"%s",LootTableSeed:1L}' % BLOCKED,
    ], False),
    ("hopper with table", (12, 100, 0), [
        "setblock 12 99 0 minecraft:hopper[facing=down]",
        'setblock 12 100 0 minecraft:hopper{LootTable:"%s",LootTableSeed:1L}' % TABLE,
    ], False),
    ("chest minecart", (16, 100, 0), [
        "setblock 16 99 0 minecraft:hopper[facing=down]",
        'summon minecraft:chest_minecart 16.5 100 0.5 {LootTable:"%s",LootTableSeed:1L,Tags:["smoke"]}' % TABLE,
    ], True),
]
MINECART_SELECTOR = "@e[type=minecraft:chest_minecart,tag=smoke,limit=1]"


# --------------------------------------------------------------------------- bands

def all_bands():
    text = (ROOT / "settings.gradle").read_text(encoding="utf-8")
    bands = re.findall(r'^include\s+"versions:([^"]+)"', text, re.M)
    for d in QUARANTINED:
        bands += [f"{d}-fabric", f"{d}-neoforge"]
    return bands


def band_layout(band):
    """(gradle working dir, runServer task, run dir) for a band."""
    ver, loader = band.rsplit("-", 1)
    if ver in QUARANTINED:
        base = ROOT / "versions" / ver
        return base, f":band-{loader}:runServer", base / f"band-{loader}" / "run"
    return ROOT, f":versions:{band}:runServer", ROOT / "versions" / band / "run"


def legacy_save_path(band, world):
    """Where the pre-rename save lives: namespaced dirs from 26.x, flat data/ before."""
    ver = band.rsplit("-", 1)[0]
    if ver in QUARANTINED:
        return world / "dimensions" / "minecraft" / "overworld" / "data" / "slashlootr" / "slashlootr.dat"
    return world / "data" / "slashlootr.dat"


# Production servers for --prod: (installer URL, launch-args file the installer writes, band whose
# shipped jar goes in mods/). Server runtime is JDK 17, vanilla 1.20.1's own Java.
PROD_TARGETS = {
    "forge": dict(
        installer="https://maven.minecraftforge.net/net/minecraftforge/forge/1.20.1-47.4.23/"
                  "forge-1.20.1-47.4.23-installer.jar",
        args_dir="libraries/net/minecraftforge/forge/1.20.1-47.4.23",
        band="1.20.1-forge"),
    "neoforge-1.20.1": dict(
        installer="https://maven.neoforged.net/releases/net/neoforged/forge/1.20.1-47.1.106/"
                  "forge-1.20.1-47.1.106-installer.jar",
        args_dir="libraries/net/neoforged/forge/1.20.1-47.1.106",
        band="1.20.1-forge"),
}


def shipped_jar(band):
    """Newest shipped jar for a band: build/release first, else the band's build/libs (reobf output)."""
    ver, loader = band.rsplit("-", 1)
    pattern = f"slashloot-*+mc{ver}-{loader}.jar"
    found = [p for d in (ROOT / "build" / "release", ROOT / "versions" / band / "build" / "libs")
             for p in d.glob(pattern) if not p.name.endswith("-sources.jar")]
    if not found:
        raise RuntimeError(f"no built jar for {band}; run ./gradlew buildAll first")
    return max(found, key=lambda p: p.stat().st_mtime)


# --------------------------------------------------------------------------- NBT (just enough)

def _nbt_read(b):
    i = 0

    def u(fmt, n):
        nonlocal i
        v = struct.unpack(">" + fmt, b[i:i + n])[0]
        i += n
        return v

    def s():
        nonlocal i
        n = u("H", 2)
        v = b[i:i + n].decode("utf-8", "replace")
        i += n
        return v

    def payload(t):
        nonlocal i
        if t in (1, 2, 3, 4, 5, 6):
            return u("bhiqfd"[t - 1], [1, 2, 4, 8, 4, 8][t - 1])
        if t == 7:
            n = u("i", 4); i += n; return None
        if t == 8:
            return s()
        if t == 9:
            et, n = u("b", 1), u("i", 4)
            return [payload(et) for _ in range(n)]
        if t == 10:
            d = {}
            while (tt := u("b", 1)) != 0:
                k = s()
                d[k] = payload(tt)
            return d
        if t in (11, 12):
            n = u("i", 4); i += n * (4 if t == 11 else 8); return None
        raise ValueError(f"bad NBT tag {t}")

    t = u("b", 1)
    s()
    return payload(t)


def _legacy_save(data_version):
    """A minimal pre-rename save: one block entry, one player, no items (item NBT varies by band)."""
    def name(k):
        e = k.encode()
        return struct.pack(">H", len(e)) + e

    player = (b"\x0b" + name("uuid") + struct.pack(">i4i", 4, 1, 2, 3, 4)
              + b"\x03" + name("size") + struct.pack(">i", 27)
              + b"\x09" + name("items") + b"\x0a" + struct.pack(">i", 0) + b"\x00")
    block = (b"\x04" + name("pos") + struct.pack(">q", 100)
             + b"\x09" + name("players") + b"\x0a" + struct.pack(">i", 1) + player + b"\x00")
    data = (b"\x09" + name("blocks") + b"\x0a" + struct.pack(">i", 1) + block
            + b"\x09" + name("entities") + b"\x0a" + struct.pack(">i", 0) + b"\x00")
    root = (b"\x0a" + name("") + b"\x0a" + name("data") + data
            + b"\x03" + name("DataVersion") + struct.pack(">i", data_version) + b"\x00")
    return gzip.compress(root)


# --------------------------------------------------------------------------- RCON

class Rcon:
    def __init__(self):
        self.sock = socket.create_connection(("127.0.0.1", RCON_PORT), timeout=10)
        self._id = 0
        if self._send(3, RCON_PASSWORD)[0] == -1:
            raise RuntimeError("RCON auth failed")

    def _send(self, kind, body):
        self._id += 1
        data = struct.pack("<ii", self._id, kind) + body.encode() + b"\0\0"
        self.sock.sendall(struct.pack("<i", len(data)) + data)
        n = struct.unpack("<i", self._recv(4))[0]
        pkt = self._recv(n)
        return struct.unpack("<i", pkt[:4])[0], pkt[8:-2].decode("utf-8", "replace")

    def _recv(self, n):
        buf = b""
        while len(buf) < n:
            chunk = self.sock.recv(n - len(buf))
            if not chunk:
                raise ConnectionError("RCON closed")
            buf += chunk
        return buf

    def cmd(self, command):
        return self._send(2, command)[1]

    def close(self):
        self.sock.close()


# --------------------------------------------------------------------------- server lifecycle

def prepare_run_dir(run):
    run.mkdir(parents=True, exist_ok=True)
    (run / "eula.txt").write_text("eula=true\n", encoding="utf-8")
    props_path = run / "server.properties"
    props = {}
    if props_path.exists():
        for line in props_path.read_text(encoding="utf-8").splitlines():
            if "=" in line and not line.startswith("#"):
                k, v = line.split("=", 1)
                props[k] = v
    props.update({
        "level-name": WORLD, "level-type": "minecraft\\:flat", "online-mode": "false",
        "server-port": str(PORT), "enable-rcon": "true", "rcon.port": str(RCON_PORT),
        "rcon.password": RCON_PASSWORD, "spawn-protection": "0", "generate-structures": "false",
    })
    props_path.write_text("".join(f"{k}={v}\n" for k, v in props.items()), encoding="utf-8")


def port_free(port):
    with socket.socket() as s:
        return s.connect_ex(("127.0.0.1", port)) != 0


def kill_tree(proc):
    if proc.poll() is not None:
        return
    if IS_WINDOWS:
        subprocess.run(["taskkill", "/T", "/F", "/PID", str(proc.pid)], capture_output=True)
    else:
        proc.kill()
    proc.wait(timeout=30)


class Server:
    def __init__(self, band, java_home, out_dir):
        self.band = band
        self.cwd, self.task, self.run = band_layout(band)
        self.log = self.run / "logs" / "latest.log"
        self.out_path = out_dir / f"smoke-{band}.gradle.log"
        wrapper = self.cwd / ("gradlew.bat" if IS_WINDOWS else "gradlew")
        self.cmd = ([str(wrapper)] if not IS_WINDOWS else ["cmd", "/c", str(wrapper)]) + \
            [self.task, "--no-daemon", "--console=plain"]
        self.env = dict(os.environ, JAVA_HOME=java_home)
        self.env["PATH"] = str(Path(java_home) / "bin") + os.pathsep + self.env["PATH"]
        self.proc = None
        self.out = None
        self.started_at = 0.0
        crash_dir = self.run / "crash-reports"
        self.crashes_before = set(crash_dir.glob("*")) if crash_dir.exists() else set()

    def start(self):
        if not (port_free(PORT) and port_free(RCON_PORT)):
            raise RuntimeError(f"ports {PORT}/{RCON_PORT} already in use - stop the other test server")
        self.started_at = time.time()
        self.out = open(self.out_path, "w", encoding="utf-8", errors="replace")
        self.proc = subprocess.Popen(self.cmd, cwd=self.cwd, env=self.env, stdin=subprocess.DEVNULL,
                                     stdout=self.out, stderr=subprocess.STDOUT)

    def log_text(self):
        if not self.log.exists() or self.log.stat().st_mtime < self.started_at - 1:
            return ""
        return self.log.read_text(encoding="utf-8", errors="replace")

    def wait_ready(self):
        deadline = time.time() + BOOT_TIMEOUT
        while time.time() < deadline:
            text = self.log_text()
            if "Done (" in text and "RCON running" in text:
                return
            if self.proc.poll() is not None:
                raise RuntimeError(f"server exited before startup finished (see {self.out_path.name})")
            time.sleep(2)
        raise RuntimeError(f"server not ready after {BOOT_TIMEOUT}s")

    def new_crash_reports(self):
        crash_dir = self.run / "crash-reports"
        now = set(crash_dir.glob("*")) if crash_dir.exists() else set()
        return sorted(p.name for p in now - self.crashes_before)

    def stop(self):
        if self.proc is None or self.proc.poll() is not None:
            return
        try:
            r = Rcon()
            r.cmd("stop")
            r.close()
            self.proc.wait(timeout=120)
        except Exception:
            pass
        kill_tree(self.proc)
        self.out.close()


class ProdServer(Server):
    """A real installer-built server running the shipped jar, instead of a Gradle dev run."""

    def __init__(self, name, java_home, out_dir, jar):
        target = PROD_TARGETS[name]
        self.band = target["band"]
        self.run = self.cwd = out_dir / f"prod-{name}"
        self.log = self.run / "logs" / "latest.log"
        self.out_path = out_dir / f"smoke-prod-{name}.server.log"
        java = str(Path(java_home) / "bin" / ("java.exe" if IS_WINDOWS else "java"))
        args_file = f"{target['args_dir']}/{'win' if IS_WINDOWS else 'unix'}_args.txt"
        self.cmd = [java, "@user_jvm_args.txt", f"@{args_file}", "nogui"]
        self.env = dict(os.environ, JAVA_HOME=java_home)
        self.proc = None
        self.out = None
        self.started_at = 0.0
        self._install(target, java, args_file, jar)
        crash_dir = self.run / "crash-reports"
        self.crashes_before = set(crash_dir.glob("*")) if crash_dir.exists() else set()

    def _install(self, target, java, args_file, jar):
        if not (self.run / args_file).exists():
            self.run.mkdir(parents=True, exist_ok=True)
            installer = self.run.parent / Path(target["installer"]).name
            if not installer.exists():
                print(f"   downloading {installer.name}", flush=True)
                req = urllib.request.Request(target["installer"], headers={"User-Agent": "slashdaemon/SlashLoot smoke"})
                with urllib.request.urlopen(req, timeout=120) as resp:  # Forge's maven 403s the default UA
                    installer.write_bytes(resp.read())
            print(f"   installing server into {self.run.relative_to(ROOT)}", flush=True)
            subprocess.run([java, "-jar", str(installer), "--installServer", str(self.run)],
                           cwd=self.run, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.STDOUT)
        mods = self.run / "mods"
        mods.mkdir(exist_ok=True)
        for old in mods.glob("slashloot*.jar"):
            old.unlink()
        shutil.copy2(jar, mods / jar.name)


class IntegratedClient(Server):
    """The band's dev client, opened straight into `smoke-world` - SlashLoot on an integrated server."""

    def __init__(self, band, java_home, out_dir):
        super().__init__(band, java_home, out_dir)
        self.out_path = out_dir / f"smoke-{band}.client.gradle.log"
        self.cmd = [a.replace(":runServer", ":runClient") for a in self.cmd] + \
            [f"--args=--quickPlaySingleplayer {WORLD}"]

    def start(self):
        self.started_at = time.time()
        self.out = open(self.out_path, "w", encoding="utf-8", errors="replace")
        self.proc = subprocess.Popen(self.cmd, cwd=self.cwd, env=self.env, stdin=subprocess.DEVNULL,
                                     stdout=self.out, stderr=subprocess.STDOUT)

    def wait_done(self):
        deadline = time.time() + CLIENT_TIMEOUT
        while time.time() < deadline:
            if "SMOKE done" in self.log_text():
                return
            if self.proc.poll() is not None:
                raise RuntimeError(f"client exited before the fixtures finished (see {self.out_path.name})")
            time.sleep(2)
        raise RuntimeError(f"no 'SMOKE done' after {CLIENT_TIMEOUT}s")

    def stop(self):
        if self.proc is not None:
            kill_tree(self.proc)
        if self.out is not None:
            self.out.close()


ERROR_RE = re.compile(r"(/ERROR\]|\[ERROR\]|/FATAL\]).*(mixin|slashloot|fabricloader|neoforge|modloading)"
                      r"|(mixin|slashloot).*(/ERROR\]|\[ERROR\])", re.I)


def startup_errors(text):
    return [line.strip()[:240] for line in text.splitlines() if ERROR_RE.search(line)]


# --------------------------------------------------------------------------- checks

def check_fixtures(server, rcon):
    results = []
    # 1.21.9 dropped spawn chunks: with no player online nothing is loaded, and setblock fails silently.
    rcon.cmd("forceload add 0 0 31 15")
    time.sleep(2)
    for _, _, setup, _ in FIXTURES:
        for c in setup:
            rcon.cmd(c)
    time.sleep(6)  # hoppers poll every 8 ticks; give every fixture several passes
    log = server.log_text()
    for name, (x, y, z), _, instanced in FIXTURES:
        if name == "chest minecart":
            reply = rcon.cmd(f"data get entity {MINECART_SELECTOR} LootTable")
            verdict = re.search(r"chest_minecart.*-> INSTANCE", log)
        else:
            reply = rcon.cmd(f"data get block {x} {y} {z} LootTable")
            verdict = re.search(rf"\[{x},{y},{z}\].*-> INSTANCE", log)
        table_kept = TABLE in reply or BLOCKED in reply
        if instanced:
            ok = table_kept and verdict is not None
            why = "" if ok else f"tag {'kept' if table_kept else 'GONE'}, INSTANCE verdict {'seen' if verdict else 'MISSING'}"
        else:
            # A missing tag alone would also pass if the setblock had failed, so require the vanilla
            # loot to have actually reached the hopper underneath.
            rolled = '"minecraft:' in rcon.cmd(f"data get block {x} {y - 1} {z} Items")
            ok = not table_kept and verdict is None and rolled
            why = "" if ok else (f"expected vanilla roll; tag {'KEPT' if table_kept else 'gone'}, "
                                 f"verdict {'INSTANCE' if verdict else 'none'}, hopper {'filled' if rolled else 'EMPTY'}")
        results.append((name, ok, why))
    return results


def seed_legacy(server):
    """Swap config + save back to their pre-rename names, as a 0.3.x server would have them."""
    run = server.run
    cfg = run / "config"
    (cfg / "slashloot.json").unlink(missing_ok=True)
    (cfg / "slashlootr.json").write_text(CONFIG, encoding="utf-8")
    world = run / WORLD
    level = _nbt_read(gzip.decompress((world / "level.dat").read_bytes()))
    data_version = level["Data"]["DataVersion"]
    for p in world.rglob("slashloot.dat"):
        p.unlink()
    legacy = legacy_save_path(server.band, world)
    legacy.parent.mkdir(parents=True, exist_ok=True)
    legacy.write_bytes(_legacy_save(data_version))


def check_migration(server, rcon):
    time.sleep(6)  # pruneIntervalTicks=40 loads the state (and so migrates) within a few seconds
    rcon.cmd("save-all flush")
    time.sleep(3)
    log = server.log_text()
    cfg = server.run / "config"
    world = server.run / WORLD
    return [
        ("config moved", "Moved config/slashlootr.json" in log and (cfg / "slashloot.json").exists(), ""),
        ("save migrated", "Migrated" in log, ""),
        ("new save written", any(world.rglob("slashloot.dat")), ""),
        ("old save kept", legacy_save_path(server.band, world).exists(), ""),
    ]


# --------------------------------------------------------------------------- integrated server

PACK = "slashsmoke"
# One pack.mcmeta for every band: pack_format + supported_formats up to 1.21.8, min/max_format from
# 1.21.9. Folder names went singular at 1.21 (functions -> function), so both spellings are written.
PACK_MCMETA = """{"pack": {"description": "SlashLoot smoke fixtures", "pack_format": 15,
  "supported_formats": {"min_inclusive": 15, "max_inclusive": 9999},
  "min_format": 15, "max_format": 9999}}
"""
CLIENT_OPTIONS = {"pauseOnLostFocus": "false", "onboardAccessibility": "false",
                  "renderDistance": "4", "simulationDistance": "5", "maxFps": "30"}


def fixture_key(name):
    return name.replace(" ", "_")


def write_fixture_pack(world):
    """The datapack that replaces RCON on an integrated server: same FIXTURES, `say` for results."""
    # The dedicated pass already ran in this world: clear its containers and filled hoppers first.
    clear = [f"kill @e[type=minecraft:chest_minecart,tag=smoke]"]
    for _, (x, y, z), _, _ in FIXTURES:
        clear += [f"setblock {x} {y} {z} minecraft:air", f"setblock {x} {y - 1} {z} minecraft:air"]
    setup = clear + [c for _, _, cmds, _ in FIXTURES for c in cmds] + \
        [f"schedule function {PACK}:check 120t"]
    check = []
    for name, (x, y, z), _, _ in FIXTURES:
        key = fixture_key(name)
        target = f"entity {MINECART_SELECTOR}" if name == "chest minecart" else f"block {x} {y} {z}"
        check += [f"execute if data {target} LootTable run say SMOKE {key} kept",
                  f"execute unless data {target} LootTable run say SMOKE {key} gone",
                  f"execute if data block {x} {y - 1} {z} Items[0] run say SMOKE {key} hopper_filled"]
    check.append("say SMOKE done")
    functions = {
        "load": ["forceload add 0 0 31 15", f"schedule function {PACK}:setup 40t"],
        "setup": setup,
        "check": check,
    }
    pack = world / "datapacks" / PACK
    shutil.rmtree(pack, ignore_errors=True)
    pack.mkdir(parents=True)
    (pack / "pack.mcmeta").write_text(PACK_MCMETA, encoding="utf-8")
    for fn_dir, tag_dir in (("functions", "functions"), ("function", "function")):
        for fn, lines in functions.items():
            p = pack / "data" / PACK / fn_dir / f"{fn}.mcfunction"
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text("\n".join(lines) + "\n", encoding="utf-8")
        tag = pack / "data" / "minecraft" / "tags" / tag_dir / "load.json"
        tag.parent.mkdir(parents=True, exist_ok=True)
        tag.write_text('{"values": ["%s:load"]}\n' % PACK, encoding="utf-8")


def prepare_client_run(run):
    """Copy the dedicated pass's world into saves/, add the fixture pack, set non-pausing options."""
    src = run / WORLD
    if not (src / "level.dat").exists():
        raise RuntimeError(f"no {WORLD} in {run} - the dedicated pass did not create it")
    dst = run / "saves" / WORLD
    shutil.rmtree(dst, ignore_errors=True)
    shutil.copytree(src, dst, ignore=shutil.ignore_patterns("session.lock"))
    write_fixture_pack(dst)
    opts_path = run / "options.txt"
    lines = opts_path.read_text(encoding="utf-8").splitlines() if opts_path.exists() else []
    keep = [ln for ln in lines if ln.split(":", 1)[0] not in CLIENT_OPTIONS]
    opts_path.write_text("\n".join(keep + [f"{k}:{v}" for k, v in CLIENT_OPTIONS.items()]) + "\n",
                         encoding="utf-8")


def check_integrated(log):
    results = [("loads in client", "SlashLoot loaded" in log, "" if "SlashLoot loaded" in log
                else "no 'SlashLoot loaded' line - Fabric did not load the mod in the client")]
    said = set(re.findall(r"SMOKE (\S+ \S+)", log))
    for name, (x, y, z), _, instanced in FIXTURES:
        key = fixture_key(name)
        kept = f"{key} kept" in said
        rolled = f"{key} hopper_filled" in said
        if name == "chest minecart":
            verdict = re.search(r"chest_minecart.*-> INSTANCE", log)
        else:
            verdict = re.search(rf"\[{x},{y},{z}\].*-> INSTANCE", log)
        if instanced:
            ok = kept and verdict is not None
            why = "" if ok else f"tag {'kept' if kept else 'GONE'}, INSTANCE verdict {'seen' if verdict else 'MISSING'}"
        else:
            ok = not kept and verdict is None and rolled
            why = "" if ok else (f"expected vanilla roll; tag {'KEPT' if kept else 'gone'}, "
                                 f"verdict {'INSTANCE' if verdict else 'none'}, hopper {'filled' if rolled else 'EMPTY'}")
        results.append((f"integrated: {name}", ok, why))
    return results


def run_integrated(band, java_home, out_dir):
    client = IntegratedClient(band, java_home, out_dir)
    checks = []
    try:
        prepare_client_run(client.run)
        client.start()
        client.wait_done()
        time.sleep(2)
        text = client.log_text()
        errors = startup_errors(text)
        checks.append(("integrated: boots clean", not errors, errors[0] if errors else ""))
        checks += check_integrated(text)
    except Exception as e:  # noqa: BLE001 - a FAIL row, keep going with other bands
        text = client.log_text()
        errors = startup_errors(text)
        checks.append(("integrated: boots clean", False, errors[0] if errors else str(e)))
        if text and "SlashLoot loaded" not in text:
            checks.append(("loads in client", False, "no 'SlashLoot loaded' line"))
    finally:
        client.stop()
    crashes = client.new_crash_reports()
    if crashes:
        checks.append(("integrated: no crash report", False, crashes[0]))
    return checks


# --------------------------------------------------------------------------- driver

def boot(make_server, fresh, body):
    """Boot one server, run `body(server, rcon)` -> checks, always stop. Returns a list of checks."""
    server = make_server()
    checks = []
    try:
        if fresh:
            shutil.rmtree(server.run / WORLD, ignore_errors=True)
            prepare_run_dir(server.run)
            (server.run / "config").mkdir(exist_ok=True)
            (server.run / "config" / "slashloot.json").write_text(CONFIG, encoding="utf-8")
        server.start()
        server.wait_ready()
        errors = startup_errors(server.log_text())
        checks.append(("boots clean", not errors, errors[0] if errors else ""))
        rcon = Rcon()
        try:
            checks += body(server, rcon)
        finally:
            rcon.close()
    except Exception as e:  # noqa: BLE001 - any failure is a FAIL row, keep going with other bands
        text = server.log_text()
        errors = startup_errors(text)
        checks.append(("boots clean", False, errors[0] if errors else str(e)))
    finally:
        server.stop()
    crashes = server.new_crash_reports()
    if crashes:
        checks.append(("no crash report", False, crashes[0]))
    return checks, server


def run_checks(make_server, migration):
    checks, server = boot(make_server, True, check_fixtures)
    if migration and all(ok for _, ok, _ in checks):
        seed_legacy(server)
        more, _ = boot(make_server, False, check_migration)
        checks += [(f"migration: {n}", ok, why) for n, ok, why in more]
    return checks


def run_band(band, java_home, out_dir, migration, integrated=False):
    checks = run_checks(lambda: Server(band, java_home, out_dir), migration)
    if integrated:
        checks += run_integrated(band, java_home, out_dir)
    return checks


def run_prod(name, java_home, out_dir, migration):
    jar = shipped_jar(PROD_TARGETS[name]["band"])
    print(f"   jar: {jar.relative_to(ROOT)}", flush=True)
    return run_checks(lambda: ProdServer(name, java_home, out_dir, jar), migration)


def main():
    global PORT, RCON_PORT
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--band", action="append", default=[], help="band to test (repeatable)")
    ap.add_argument("--all", action="store_true", help="test every band")
    ap.add_argument("--migration", action="store_true", help="also test the slashlootr -> slashloot migration")
    ap.add_argument("--prod", action="append", default=[], choices=sorted(PROD_TARGETS),
                    help="test the shipped jar on a real installer-built server (repeatable)")
    ap.add_argument("--prod-java-home", default=str(Path.home() / "AppData/Roaming/PrismLauncher/java/java-runtime-gamma"),
                    help="JDK for --prod servers (default: Prism's JDK 17)")
    ap.add_argument("--integrated", action="store_true",
                    help="also open each Fabric band's dev client on the world (singleplayer); "
                         "opens a game window; with --all, runs the Fabric bands only")
    ap.add_argument("--port", type=int, default=PORT, help="server port; RCON uses port + 1")
    ap.add_argument("--java-home", default=os.environ.get("JAVA_HOME")
                    or str(Path.home() / "AppData/Roaming/PrismLauncher/java/java-runtime-delta"))
    args = ap.parse_args()
    PORT, RCON_PORT = args.port, args.port + 1

    known = all_bands()
    bands = known if args.all else args.band
    if not bands and not args.prod:
        ap.error("pass --all, --band or --prod")
    unknown = [b for b in bands if b not in known]
    if unknown:
        ap.error(f"unknown band(s) {unknown}; known: {', '.join(known)}")
    if args.integrated:
        if args.all:
            bands = [b for b in bands if b.endswith("-fabric")]
        not_fabric = [b for b in bands if not b.endswith("-fabric")]
        if not_fabric:
            ap.error(f"--integrated is a Fabric-only pass; drop {not_fabric}")

    out_dir = ROOT / "build" / "smoke"
    out_dir.mkdir(parents=True, exist_ok=True)
    runs = [(band, lambda b=band: run_band(b, args.java_home, out_dir, args.migration, args.integrated))
            for band in bands]
    runs += [(f"prod:{name}", lambda n=name: run_prod(n, args.prod_java_home, out_dir, args.migration))
             for name in args.prod]
    summary = []
    for label, run in runs:
        t0 = time.time()
        print(f"== {label}", flush=True)
        try:
            checks = run()
        except Exception as e:  # noqa: BLE001 - e.g. no built jar, installer failure
            checks = [("setup", False, str(e))]
        for name, ok, why in checks:
            print(f"   {'PASS' if ok else 'FAIL'}  {name}{'  - ' + why if why else ''}", flush=True)
        passed = all(ok for _, ok, _ in checks)
        summary.append((label, passed, time.time() - t0))

    print("\nSummary")
    for band, passed, secs in summary:
        print(f"  {'PASS' if passed else 'FAIL'}  {band:<22} {secs:5.0f}s")
    failed = [b for b, p, _ in summary if not p]
    print(f"\n{len(summary) - len(failed)}/{len(summary)} bands passed"
          + (f"; server output in {out_dir.relative_to(ROOT)}" if failed else ""))
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()

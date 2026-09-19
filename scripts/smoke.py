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

Usage:
    python scripts/smoke.py --all
    python scripts/smoke.py --band 1.20.1-fabric --band 26.3-neoforge --migration

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
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
QUARANTINED = ["26.1.2", "26.2", "26.3"]
WORLD = "smoke-world"
PORT, RCON_PORT, RCON_PASSWORD = 25594, 25595, "slashtest"  # --port overrides; RCON is port + 1
BOOT_TIMEOUT = 420
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


# --------------------------------------------------------------------------- driver

def boot(band, java_home, out_dir, fresh, body):
    """Boot one server, run `body(server, rcon)` -> checks, always stop. Returns a list of checks."""
    server = Server(band, java_home, out_dir)
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


def run_band(band, java_home, out_dir, migration):
    checks, server = boot(band, java_home, out_dir, True, check_fixtures)
    if migration and all(ok for _, ok, _ in checks):
        seed_legacy(server)
        more, _ = boot(band, java_home, out_dir, False, check_migration)
        checks += [(f"migration: {n}", ok, why) for n, ok, why in more]
    return checks


def main():
    global PORT, RCON_PORT
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--band", action="append", default=[], help="band to test (repeatable)")
    ap.add_argument("--all", action="store_true", help="test every band")
    ap.add_argument("--migration", action="store_true", help="also test the slashlootr -> slashloot migration")
    ap.add_argument("--port", type=int, default=PORT, help="server port; RCON uses port + 1")
    ap.add_argument("--java-home", default=os.environ.get("JAVA_HOME")
                    or str(Path.home() / "AppData/Roaming/PrismLauncher/java/java-runtime-delta"))
    args = ap.parse_args()
    PORT, RCON_PORT = args.port, args.port + 1

    known = all_bands()
    bands = known if args.all else args.band
    if not bands:
        ap.error("pass --all or at least one --band")
    unknown = [b for b in bands if b not in known]
    if unknown:
        ap.error(f"unknown band(s) {unknown}; known: {', '.join(known)}")

    out_dir = ROOT / "build" / "smoke"
    out_dir.mkdir(parents=True, exist_ok=True)
    summary = []
    for band in bands:
        t0 = time.time()
        print(f"== {band}", flush=True)
        checks = run_band(band, args.java_home, out_dir, args.migration)
        for name, ok, why in checks:
            print(f"   {'PASS' if ok else 'FAIL'}  {name}{'  - ' + why if why else ''}", flush=True)
        passed = all(ok for _, ok, _ in checks)
        summary.append((band, passed, time.time() - t0))

    print("\nSummary")
    for band, passed, secs in summary:
        print(f"  {'PASS' if passed else 'FAIL'}  {band:<18} {secs:5.0f}s")
    failed = [b for b, p, _ in summary if not p]
    print(f"\n{len(summary) - len(failed)}/{len(summary)} bands passed"
          + (f"; gradle output in {out_dir.relative_to(ROOT)}" if failed else ""))
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()

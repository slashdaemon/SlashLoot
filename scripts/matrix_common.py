"""Shared plumbing for boot-matrix.py and client-matrix.py. Standard library only.

Everything runs out of WORK (default %TEMP%/slashloot-matrix, override with SLASHLOOT_MATRIX_DIR), so
LocalServer's mods, properties and installs are never touched. Servers use LocalServer's ports
(25565, RCON 25575), so stop anything else on those first.

Servers are started DETACHED_PROCESS with output in a log file: never spawn a console from a tool on
the Windows box, it takes Windows Terminal down.
"""
import ast
import json
import os
import re
import shutil
import socket
import struct
import subprocess
import tempfile
import time
import urllib.parse
import urllib.request
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
RELEASE = REPO / "build" / "release"
WORK = Path(os.environ.get("SLASHLOOT_MATRIX_DIR", Path(tempfile.gettempdir()) / "slashloot-matrix"))
CACHE = WORK / "cache"
LOGS = WORK / "logs"
RESULTS = WORK / "results"
PRISM_JAVA = Path(os.environ["APPDATA"]) / "PrismLauncher" / "java"
UA = {"User-Agent": "slashdaemon/SlashLootr test matrix"}
TABLE = "minecraft:chests/simple_dungeon"
BLOCKED_TABLE = "minecraft:chests/igloo_chest"


def mod_version():
    return re.search(r"(?m)^mod_version=(\S+)", (REPO / "gradle.properties").read_text()).group(1)


def advertised():
    """[(band, loader, mc)] from the publish script, so what is listed is what gets tested."""
    tree = ast.parse((REPO / "scripts" / "publish-modrinth.py").read_text(encoding="utf8"))
    for node in tree.body:
        if isinstance(node, ast.Assign) and getattr(node.targets[0], "id", "") == "BAND_GAME_VERSIONS":
            table = ast.literal_eval(node.value)
            return [(b, l, mc) for (b, l), mcs in table.items() for mc in mcs]
    raise SystemExit("BAND_GAME_VERSIONS not found")


def primary_mc(band, loader):
    """The MC version a jar was compiled against."""
    props = REPO / "versions" / (band if band.startswith("26.") else f"{band}-{loader}") / "gradle.properties"
    return re.search(r"(?m)^minecraft_version=(\S+)", props.read_text()).group(1)


def pinned_neoforge(band):
    props = REPO / "versions" / (band if band.startswith("26.") else f"{band}-neoforge") / "gradle.properties"
    return re.search(r"(?m)^neoforge_version=(\S+)", props.read_text()).group(1)


def jar_path(band, loader, version=None):
    return RELEASE / f"slashlootr-{version or mod_version()}+mc{band}-{loader}.jar"


def java_for(mc):
    # Prism refuses to launch 1.20.1 on anything but Java 17; 26.x needs 25.
    alias = "java-runtime-epsilon" if mc.startswith("26.") else "java-runtime-gamma" if mc == "1.20.1" else "java-runtime-delta"
    return PRISM_JAVA / alias / "bin" / "java.exe"


def get(url, dest=None):
    req = urllib.request.Request(url, headers=UA)
    with urllib.request.urlopen(req, timeout=120) as r:
        data = r.read()
    if dest is None:
        return data
    dest.parent.mkdir(parents=True, exist_ok=True)
    tmp = dest.with_suffix(dest.suffix + ".part")
    tmp.write_bytes(data)
    tmp.replace(dest)
    return dest


def fabric_server(mc):
    dest = CACHE / f"fabric-server-{mc}.jar"
    if dest.exists():
        return dest
    loader = json.loads(get(f"https://meta.fabricmc.net/v2/versions/loader/{mc}"))
    loader = next(x for x in loader if x["loader"]["stable"])["loader"]["version"]
    installer = json.loads(get("https://meta.fabricmc.net/v2/versions/installer"))[0]["version"]
    return get(f"https://meta.fabricmc.net/v2/versions/loader/{mc}/{loader}/{installer}/server/jar", dest)


def fabric_api(mc):
    q = urllib.parse.urlencode({"game_versions": json.dumps([mc]), "loaders": json.dumps(["fabric"])})
    versions = json.loads(get(f"https://api.modrinth.com/v2/project/fabric-api/version?{q}"))
    f = next(x for x in versions[0]["files"] if x["primary"])
    dest = CACHE / f["filename"]
    return dest if dest.exists() else get(f["url"], dest)


def neoforge_for_mc(mc):
    """Newest NeoForge for an MC version, stable preferred. None if the MC version has none."""
    if mc.startswith("26."):
        prefix = mc + "."
    else:
        parts = mc.split(".")
        prefix = f"{parts[1]}.{parts[2] if len(parts) > 2 else 0}."
    listing = json.loads(get("https://maven.neoforged.net/api/maven/versions/releases/net/neoforged/neoforge"))
    found = [v for v in listing["versions"] if v.startswith(prefix)]
    stable = [v for v in found if "beta" not in v and "alpha" not in v]
    return (stable or found or [None])[-1]


def neoforge_install(version):
    """Installs into WORK/nf/<version> and returns that directory."""
    home = WORK / "nf" / version
    args = home / "libraries" / "net" / "neoforged" / "neoforge" / version / "win_args.txt"
    if args.exists():
        return home
    installer = CACHE / f"neoforge-{version}-installer.jar"
    if not installer.exists():
        get(f"https://maven.neoforged.net/releases/net/neoforged/neoforge/{version}/neoforge-{version}-installer.jar", installer)
    home.mkdir(parents=True, exist_ok=True)
    LOGS.mkdir(parents=True, exist_ok=True)
    java = java_for("26." if version.startswith("26.") else "1.")
    with open(LOGS / f"nf-install-{version}.log", "wb") as log:
        subprocess.run([str(java), "-jar", str(installer), "--installServer", str(home)], cwd=home,
                       stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT, timeout=900,
                       creationflags=0x8)
    if not args.exists():
        raise RuntimeError(f"NeoForge {version} did not install")
    return home


class Rcon:
    def __init__(self, port=25575, password="testpassword"):
        self.s = socket.create_connection(("127.0.0.1", port), timeout=15)
        self.i = 0
        self._send(3, password)

    def _send(self, kind, body):
        self.i += 1
        data = struct.pack("<ii", self.i, kind) + body.encode() + b"\0\0"
        self.s.sendall(struct.pack("<i", len(data)) + data)
        head = b""
        while len(head) < 4:
            head += self.s.recv(4 - len(head))
        size = struct.unpack("<i", head)[0]
        out = b""
        while len(out) < size:
            out += self.s.recv(size - len(out))
        return out[8:-2].decode("utf8", "replace")

    def __call__(self, command):
        return self._send(2, command)

    def close(self):
        self.s.close()


PROPS = """online-mode=false
enforce-secure-profile=false
level-name={level}
level-type=minecraft\\:flat
difficulty=peaceful
spawn-monsters=false
gamemode=creative
force-gamemode=true
spawn-protection=0
enable-rcon=true
rcon.port=25575
rcon.password=testpassword
broadcast-rcon-to-ops=false
server-port=25565
view-distance=6
simulation-distance=6
"""


class Server:
    """One (jar, MC) pair. `level` is kept between start() calls, so restarts reuse the world."""

    def __init__(self, band, loader, mc, jar=None, level=None, neoforge=None, config=None, fresh=True):
        self.band, self.loader, self.mc = band, loader, mc
        self.jar = Path(jar) if jar else jar_path(band, loader)
        self.level = level or ("world-" + re.sub(r"\W", "_", f"{band}-{loader}-{mc}"))
        self.neoforge = neoforge
        self.config = config
        self.log = LOGS / f"server-{band}-{loader}-{mc}.log"
        self.proc = None
        self.rcon = None
        self.logfile = None
        self.fresh = fresh

    def _prepare(self):
        if self.loader == "fabric":
            self.home = WORK / "fabric"
            self.home.mkdir(parents=True, exist_ok=True)
            launcher = fabric_server(self.mc)
            extras = [fabric_api(self.mc)]
            cmd = ["-jar", str(launcher), "nogui"]
        else:
            version = self.neoforge or neoforge_for_mc(self.mc)
            if version is None:
                raise RuntimeError(f"no NeoForge exists for MC {self.mc}")
            self.nf_version = version
            self.home = neoforge_install(version)
            extras = []
            cmd = [f"@libraries/net/neoforged/neoforge/{version}/win_args.txt", "nogui"]
        if self.fresh:
            shutil.rmtree(self.home / self.level, ignore_errors=True)
            self.fresh = False
        mods = self.home / "mods"
        shutil.rmtree(mods, ignore_errors=True)
        mods.mkdir()
        for path in [self.jar, *extras]:
            shutil.copy(path, mods)
        (self.home / "eula.txt").write_text("eula=true\n")
        (self.home / "server.properties").write_text(PROPS.format(level=self.level))
        (self.home / "config").mkdir(exist_ok=True)
        cfg = self.home / "config" / "slashlootr.json"
        cfg.unlink(missing_ok=True)
        if self.config:
            cfg.write_text(json.dumps(self.config))
        return cmd

    def start(self, timeout=300):
        cmd = self._prepare()
        LOGS.mkdir(parents=True, exist_ok=True)
        self.logfile = open(self.log, "ab")
        self.start_at = self.log.stat().st_size
        self.proc = subprocess.Popen([str(java_for(self.mc)), "-Xmx3G", *cmd], cwd=self.home,
                                     stdin=subprocess.DEVNULL, stdout=self.logfile, stderr=subprocess.STDOUT,
                                     creationflags=0x8 | 0x200)
        return self.wait_for(r"Done \(", timeout) and self._connect()

    def _connect(self):
        for _ in range(40):
            try:
                self.rcon = Rcon()
                return True
            except OSError:
                time.sleep(0.5)
        return False

    def text(self):
        return self.log.read_bytes()[self.start_at:].decode("utf8", "replace")

    def wait_for(self, pattern, timeout):
        end = time.time() + timeout
        while time.time() < end:
            if re.search(pattern, self.text()):
                return True
            if self.proc.poll() is not None:
                return False
            time.sleep(0.5)
        return False

    def mod_errors(self):
        pat = re.compile(r"Mixin apply.*slashlootr|slashlootr.*(failed|error)|Caused by.*slashlootr"
                         r"|Exception.*dev\.blockacademy|at dev\.blockacademy", re.I)
        return [l.strip()[:220] for l in self.text().splitlines() if pat.search(l)][:6]

    def stop(self):
        try:
            if self.rcon:
                self.rcon("stop")
                self.rcon.close()
        except OSError:
            pass
        self.rcon = None
        if self.proc:
            try:
                self.proc.wait(60)
            except subprocess.TimeoutExpired:
                self.proc.kill()
                self.proc.wait(20)
            self.proc = None
        if self.logfile:
            self.logfile.close()
            self.logfile = None


def nbt_loot(table, seed):
    return '{LootTable:"%s",LootTableSeed:%dL}' % (table, seed)


def chest_boat_id(mc):
    """Boats became one entity per wood type at 1.21.2."""
    if mc.startswith("26."):
        return "minecraft:oak_chest_boat"
    parts = [int(p) for p in mc.split(".")] + [0]
    return "minecraft:oak_chest_boat" if (parts[1], parts[2]) >= (21, 2) else "minecraft:chest_boat"

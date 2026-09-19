#!/usr/bin/env python3
"""
Publish SlashLoot per-band JARs to CurseForge.

SlashLoot ships one JAR per (MC version band, loader) — no platform variants.
Each (band) becomes one CurseForge file. This is the simplified cousin of
StreamCraft's per-platform-variant flow: same project ID resolution, same
game-version catalog handling, same dry-run + selective-bands ergonomics —
just no primary/additional-file parent linkage to deal with.

Usage:
    # Dry-run all bands to inspect metadata before publishing
    python scripts/publish-curseforge.py --version 0.1.0 --dry-run

    # Publish all bands
    python scripts/publish-curseforge.py --version 0.1.0

    # Publish a subset
    python scripts/publish-curseforge.py --version 0.1.0 --bands 1.21.1,1.21.4

    # Publish a single band as a beta
    python scripts/publish-curseforge.py --version 0.1.0 --bands 1.21.9 --type beta

Auth via CURSEFORGE_TOKEN env var or .env file in the repo root. Generate a
token at https://authors-old.curseforge.com/account/api-tokens (needs upload
permission on the target project).

Project ID via --project-id or $CURSEFORGE_PROJECT_ID. The .env in the repo
root is the canonical source.

The script fetches /api/game/versions once at the start and resolves
("1.21.1", "Fabric", "Java 21") to CurseForge integer IDs. Catalog misses
print a warning so you can verify the upstream hasn't drifted.

Idempotency: CurseForge's API has no "find existing version" lookup, so
re-running after a partial failure may double-upload. Delete partial files
through the CF dashboard before retrying, or use --bands to scope the retry
to specific bands.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import time
from pathlib import Path

try:
    import requests
except ImportError:
    print("ERR: requests not installed. Install with: pip install requests")
    sys.exit(1)

try:
    import markdown as _markdown
except ImportError:
    _markdown = None  # falls back to text mode if package not installed


CURSEFORGE_API = "https://minecraft.curseforge.com/api"

# Per-band Minecraft versions to advertise. Each band's JAR is compiled against
# the version named in its directory; the band can additionally claim adjacent
# versions that share the same vanilla API surface. See docs/ARCHITECTURE.md
# § Per-band specifics for what bands actually cover.
# Keyed by (mc_band, loader) because the two loaders do not cover the same MC versions:
# NeoForge has no line below 1.20.5, and NF 21.6 / 21.7 / 21.9 have no stable builds at all
# (every published 21.6.x / 21.9.x is a -beta), so those MC versions ride the 21.8 and 21.10 JARs.
#
# NOTE: 1.21.4 does NOT cover 1.21.5. SavedData.Factory was removed at 1.21.5, not 1.21.6 as
# earlier metadata assumed — verified by compiling the CompoundTag store against 1.21.5 and
# watching it fail. 0.1.x advertised the 1.21.4 JAR for 1.21.5; it would not have run there.
# 1.21.5 now has its own band on both loaders.
BAND_GAME_VERSIONS = {
    ("1.20.1",  "fabric"):   ["1.20.1"],
    ("1.20.5",  "fabric"):   ["1.20.5", "1.20.6"],
    ("1.21",    "fabric"):   ["1.21"],
    ("1.21.1",  "fabric"):   ["1.21.1"],
    ("1.21.2",  "fabric"):   ["1.21.2", "1.21.3"],
    ("1.21.4",  "fabric"):   ["1.21.4"],
    ("1.21.5",  "fabric"):   ["1.21.5"],
    ("1.21.6",  "fabric"):   ["1.21.6", "1.21.7", "1.21.8"],
    ("1.21.9",  "fabric"):   ["1.21.9", "1.21.10"],
    ("1.21.11", "fabric"):   ["1.21.11"],
    ("26.1.2",  "fabric"):   ["26.1.2"],
    ("26.2",    "fabric"):   ["26.2"],
    ("26.3",    "fabric"):   ["26.3"],

    ("1.20.6",  "neoforge"): ["1.20.6"],
    ("1.21.1",  "neoforge"): ["1.21", "1.21.1"],
    ("1.21.3",  "neoforge"): ["1.21.2", "1.21.3"],
    ("1.21.4",  "neoforge"): ["1.21.4"],
    ("1.21.5",  "neoforge"): ["1.21.5"],
    ("1.21.8",  "neoforge"): ["1.21.6", "1.21.7", "1.21.8"],
    ("1.21.10", "neoforge"): ["1.21.9", "1.21.10"],
    ("1.21.11", "neoforge"): ["1.21.11"],
    ("26.1.2",  "neoforge"): ["26.1.2"],
    ("26.2",    "neoforge"): ["26.2"],
    ("26.3",    "neoforge"): ["26.3"],
    # MinecraftForge: 1.20.1 only. Built against Forge 47.1.3, so the same file runs on NeoForge 1.20.1.
    ("1.20.1",  "forge"):    ["1.20.1"],
}


def game_versions_for(band: str, loader: str) -> list[str]:
    """MC versions a given (band, loader) JAR is advertised for."""
    return BAND_GAME_VERSIONS.get((band, loader), [band])


# (band, loader) pairs built on a beta loader. These always publish as `beta`, whatever --type says,
# so a stable release never advertises a JAR whose loader can still break underneath it.
BETA_ONLY_BANDS = {
    ("26.3", "neoforge"),  # NeoForge 26.3 has no stable build yet (26.3.0.x-beta)
}


def release_type_for(band: str, loader: str, requested: str) -> str:
    """--type, downgraded to `beta` for bands whose loader is itself beta-only."""
    if (band, loader) in BETA_ONLY_BANDS and requested == "release":
        return "beta"
    return requested

# Per-band Java version. Band A (1.20.1) needs JDK 17; 1.20.5–1.21.11 use JDK 21.
# Band G (26.1.2) compiles with JDK 25 but we tag "Java 21" since CF doesn't yet
# recognize "Java 25" in its catalog (matches StreamCraft's 26.1 convention).
# Band A (1.20.1) needs JDK 17; everything else is JDK 21. Bands G/J (26.x) compile with JDK 25
# but are tagged "Java 21" because CF does not yet carry "Java 25" (matches StreamCraft's
# 26.1 convention).
BAND_JAVA_VERSION = {
    "1.20.1": "Java 17",
}


def java_version_for(band: str) -> str:
    return BAND_JAVA_VERSION.get(band, "Java 21")


# CurseForge requires at least one tag from its "Environment" version group, or the upload is
# rejected with errorCode 1021. SlashLoot is server-side only (fabric.mod.json declares
# environment=server, and clients connect unmodified), so every file is tagged Server.
CURSEFORGE_ENVIRONMENT = "Server"


# CurseForge "Modloader" tags per JAR loader. The Forge 1.20.1 jar is built against Forge 47.1.3,
# which NeoForge's 1.20.1 line is a superset of, so the one file carries both tags.
CF_LOADER_TAGS = {
    "fabric": ["Fabric"],
    "neoforge": ["NeoForge"],
    "forge": ["Forge", "NeoForge"],
}


def parse_filename(jar_path: Path) -> tuple[str, str, str]:
    """
    Parse a JAR filename into (mod_version, mc_band, loader).
    Expected shape: slashloot-<ver>+mc<band>-<loader>.jar
    """
    name = jar_path.stem
    m = re.match(r"^slashloot-([^+]+)\+mc([0-9.]+)-(fabric|neoforge|forge)$", name)
    if not m:
        raise ValueError(f"Cannot parse filename: {jar_path.name}")
    return m.group(1), m.group(2), m.group(3)


def discover_jars(release_dir: Path, mod_version: str) -> dict[tuple[str, str], Path]:
    """Return {(mc_band, loader): jar_path} for the given mod version."""
    found: dict[tuple[str, str], Path] = {}
    for jar in sorted(release_dir.glob(f"slashloot-{mod_version}+mc*.jar")):
        if jar.name.endswith("-sources.jar") or jar.name.endswith("-dev.jar"):
            continue
        try:
            ver, band, loader = parse_filename(jar)
        except ValueError as e:
            print(f"  skip: {e}")
            continue
        if ver != mod_version:
            continue
        found[(band, loader)] = jar
    return found


def extract_changelog(changelog_path: Path, mod_version: str, full_file: bool) -> str:
    if not changelog_path.exists():
        return ""
    text = changelog_path.read_text(encoding="utf-8")
    if full_file:
        return text.strip()
    # CHANGELOG.md uses "## X.Y.Z — YYYY-MM-DD" heading format. Match the
    # leading version after "## " optionally prefixed with "v".
    pattern = rf"## v?{re.escape(mod_version)}\b.*?(?=\n## v?\d|\Z)"
    m = re.search(pattern, text, re.DOTALL)
    return m.group(0).strip() if m else ""


def render_changelog(markdown_text: str, fmt: str) -> tuple[str, str]:
    """
    Returns (rendered, changelogType) for upload. CurseForge's "markdown"
    changelogType doesn't render reliably (raw markup leaks through), so by
    default we convert to HTML client-side and send changelogType="html".
    """
    if fmt == "html":
        if _markdown is None:
            print("WARN python-markdown not installed; install via 'pip install markdown'. "
                  "Falling back to changelogType=text.")
            return markdown_text, "text"
        # `tables` enables GFM-style tables (used heavily in our CHANGELOG).
        return _markdown.markdown(markdown_text, extensions=["tables", "fenced_code"]), "html"
    if fmt == "markdown":
        return markdown_text, "markdown"
    return markdown_text, "text"


def fetch_game_versions(token: str) -> dict[tuple[int, str], int]:
    """
    Fetch the CurseForge game-version catalog and return a (typeId, name)->id
    map. CurseForge has multiple entries per name across version-type buckets
    (e.g. "1.21.1" exists under multiple bucket types). Uploads must reference
    the *Minecraft* type for MC versions, "Modloader" for Fabric, and "Java"
    for the Java version.
    """
    hdr = {"X-Api-Token": token}
    r = requests.get(f"{CURSEFORGE_API}/game/versions", headers=hdr, timeout=30)
    r.raise_for_status()
    catalog: dict[tuple[int, str], int] = {}
    for v in r.json():
        catalog[(v["gameVersionTypeID"], v["name"])] = v["id"]
    return catalog


def fetch_version_type_ids(token: str) -> dict[str, int]:
    """Return slug->typeId map from /api/game/version-types."""
    r = requests.get(
        f"{CURSEFORGE_API}/game/version-types",
        headers={"X-Api-Token": token},
        timeout=30,
    )
    r.raise_for_status()
    return {t["slug"]: t["id"] for t in r.json()}


def expected_type_slug(name: str) -> str | None:
    """
    Derive the version-type slug a name lives under.
      "1.21.1"  -> "minecraft-1-21"
      "1.20.5"  -> "minecraft-1-20"
      "Fabric"  -> "modloader"
      "NeoForge"-> "modloader"
      "Java 21" -> "java"
      "Server"  -> "environment"
    """
    if name in ("Client", "Server"):
        return "environment"
    if name in ("Fabric", "NeoForge", "Forge"):
        return "modloader"
    if name.startswith("Java "):
        return "java"
    m = re.match(r"^(\d+)\.(\d+)(?:\.\d+)?$", name)
    if m:
        major, minor = m.group(1), m.group(2)
        return f"minecraft-{major}-{minor}"
    return None


def resolve_game_version_ids(
    catalog: dict[tuple[int, str], int],
    type_ids: dict[str, int],
    mc_versions: list[str],
    java_version: str,
    band: str,
    loader_names: list[str],
) -> list[int]:
    """Build the gameVersions int-ID array CurseForge expects."""
    requested = [*mc_versions, *loader_names, java_version, CURSEFORGE_ENVIRONMENT]
    ids: list[int] = []
    missing: list[str] = []
    for name in requested:
        slug = expected_type_slug(name)
        type_id = type_ids.get(slug) if slug else None
        cf_id = catalog.get((type_id, name)) if type_id else None
        if cf_id is None:
            missing.append(f"{name} (slug={slug})")
        else:
            ids.append(cf_id)
    if missing:
        print(f"  WARN band {band}: CF catalog missing {missing}; "
              f"those entries will not appear on the file's version list")
    return ids


def upload_file(
    project_id: int,
    jar: Path,
    metadata: dict,
    token: str,
) -> int:
    """POST one file. Returns the integer fileID assigned by CurseForge."""
    url = f"{CURSEFORGE_API}/projects/{project_id}/upload-file"
    size_mb = jar.stat().st_size / 1_000_000
    print(f"  POST {url} ({size_mb:.2f} MB, {jar.name}) ...")
    with jar.open("rb") as fh:
        files = {
            "metadata": (None, json.dumps(metadata), "application/json"),
            "file":     (jar.name, fh, "application/java-archive"),
        }
        r = requests.post(
            url,
            headers={"X-Api-Token": token},
            files=files,
            timeout=600,
        )
    if r.status_code >= 400:
        raise RuntimeError(f"CurseForge {r.status_code}: {r.text}")
    data = r.json()
    file_id = data.get("id")
    if not isinstance(file_id, int):
        raise RuntimeError(f"CurseForge returned no file id: {data}")
    print(f"    OK fileID={file_id}")
    return file_id


def upload_band(
    project_id: int,
    mod_version: str,
    band: str,
    loader: str,
    jar: Path,
    game_version_ids: list[int],
    release_type: str,
    changelog: str,
    changelog_type: str,
    token: str,
    dry_run: bool,
) -> bool:
    """Upload one (band, loader) JAR. Returns True on success, False on dry-run."""
    print(f"\n=== {mod_version}+mc{band}-{loader} ===")

    metadata = {
        "changelog": changelog,
        "changelogType": changelog_type,
        "displayName": f"slashloot-{mod_version}+mc{band}-{loader}.jar",
        "gameVersions": game_version_ids,
        "releaseType": release_type,
    }

    if dry_run:
        print(f"  DRY-RUN file: {jar.name}")
        print(f"  DRY-RUN metadata:\n{json.dumps(metadata, indent=2)}")
        return False

    upload_file(project_id, jar, metadata, token)
    return True


def load_dotenv(path: Path) -> None:
    """Minimal .env loader: KEY=VALUE per line, no quoting/expansion."""
    if not path.exists():
        return
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, value = line.partition("=")
        key, value = key.strip(), value.strip()
        if key and value and key not in os.environ:
            os.environ[key] = value


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--project-id", type=int, default=None,
                   help="CurseForge numeric project ID. Falls back to $CURSEFORGE_PROJECT_ID "
                        "(loaded from .env) when omitted.")
    p.add_argument("--version", required=True, help="Mod version (e.g. 0.1.0)")
    p.add_argument("--type", default="release", choices=["release", "beta", "alpha"])
    p.add_argument("--release-dir", default="build/release", help="Directory containing JARs")
    p.add_argument("--changelog-file", default="CHANGELOG.md", help="Path to changelog file")
    p.add_argument("--bands", help="Comma-separated MC bands to upload (default: all discovered)")
    p.add_argument("--loaders", help="Comma-separated loaders to upload: fabric,neoforge,forge (default: all discovered)")
    p.add_argument("--changelog-format", default="html", choices=["html", "markdown", "text"],
                   help="changelogType to send. Default 'html' (we convert the .md source to "
                        "HTML client-side because CurseForge's 'markdown' type renders raw markup).")
    p.add_argument("--dry-run", action="store_true", help="Print metadata without uploading")
    args = p.parse_args()

    project_root = Path(__file__).resolve().parent.parent
    load_dotenv(project_root / ".env")

    token = os.environ.get("CURSEFORGE_TOKEN", "")
    if not token and not args.dry_run:
        print("ERR CURSEFORGE_TOKEN not set (env var or .env file)")
        return 1

    # Resolve project ID: CLI arg wins; otherwise pull from env.
    if args.project_id is None:
        env_pid = os.environ.get("CURSEFORGE_PROJECT_ID", "").strip()
        if not env_pid:
            print("ERR --project-id not given and CURSEFORGE_PROJECT_ID not set in env/.env")
            return 1
        try:
            args.project_id = int(env_pid)
        except ValueError:
            print(f"ERR CURSEFORGE_PROJECT_ID is not an int: {env_pid!r}")
            return 1
        print(f"Using CURSEFORGE_PROJECT_ID={args.project_id} from .env")

    release_dir = (project_root / args.release_dir).resolve()
    if not release_dir.is_dir():
        print(f"ERR release dir not found: {release_dir}")
        return 1

    found = discover_jars(release_dir, args.version)
    if not found:
        print(f"ERR no JARs found matching version {args.version} in {release_dir}")
        return 1

    if args.bands:
        wanted = {b.strip() for b in args.bands.split(",")}
        found = {k: v for k, v in found.items() if k[0] in wanted}
        if not found:
            print(f"ERR no bands matching --bands={args.bands}")
            return 1

    if args.loaders:
        wanted_loaders = {l.strip().lower() for l in args.loaders.split(",")}
        found = {k: v for k, v in found.items() if k[1] in wanted_loaders}
        if not found:
            print(f"ERR no files matching --loaders={args.loaders}")
            return 1

    print(f"Discovered {len(found)} file(s) for v{args.version}: "
          f"{[f'{b}-{l}' for b, l in sorted(found)]}")

    full_file = (args.changelog_file != "CHANGELOG.md")
    raw_changelog = extract_changelog(project_root / args.changelog_file, args.version, full_file)
    if not raw_changelog:
        print(f"WARN no changelog section found for v{args.version} in {args.changelog_file}")
    changelog, changelog_type = render_changelog(raw_changelog, args.changelog_format)
    first_line = (raw_changelog.splitlines()[0] if raw_changelog else "")
    print(f"Changelog: {first_line} ({len(changelog)} chars, type={changelog_type})")

    # Resolve gameVersion integer IDs once for the whole run.
    catalog: dict[tuple[int, str], int] = {}
    type_ids: dict[str, int] = {}
    if token:
        try:
            type_ids = fetch_version_type_ids(token)
            catalog = fetch_game_versions(token)
            print(f"Loaded {len(catalog)} CF game-version entries across {len(type_ids)} version-types")
        except Exception as e:
            print(f"ERR could not fetch CF game-version catalog: {e}")
            if not args.dry_run:
                return 1
    elif not args.dry_run:
        return 1

    failures = 0
    successes = 0
    for band, loader in sorted(found):
        jar = found[(band, loader)]
        loader_names = CF_LOADER_TAGS[loader]
        mc_versions = game_versions_for(band, loader)
        java_version = java_version_for(band)
        if catalog:
            game_version_ids = resolve_game_version_ids(
                catalog, type_ids, mc_versions, java_version, band, loader_names)
        else:
            game_version_ids = [-1]  # dry-run placeholder
        try:
            if upload_band(
                project_id=args.project_id,
                mod_version=args.version,
                band=band,
                loader=loader,
                jar=jar,
                game_version_ids=game_version_ids,
                release_type=release_type_for(band, loader, args.type),
                changelog=changelog,
                changelog_type=changelog_type,
                token=token,
                dry_run=args.dry_run,
            ):
                successes += 1
        except Exception as e:
            print(f"  FAILED: {e}")
            failures += 1
        # Small stagger between uploads to be polite to the CF API.
        if not args.dry_run:
            time.sleep(0.5)

    skipped = len(found) - successes - failures
    print(f"\nDone - {successes} uploaded, {failures} failed, {skipped} skipped")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())

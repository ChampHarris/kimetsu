#!/usr/bin/env python3
"""
refsrc.py — put the exact Minecraft + NeoForge sources (and optionally vanilla assets and library sources)
where Claude can grep them: ref-src/. This is what "verify every API in ref-src/" means.

  python tools/refsrc.py            # Minecraft + NeoForge sources -> ref-src/minecraft/
  python tools/refsrc.py --assets   # + vanilla assets/minecraft -> ref-src/vanilla/ (for vanilla_coverage.py)
  python tools/refsrc.py --libs     # + sources of pinned libraries found in the Gradle cache -> ref-src/libs/<name>/
  python tools/refsrc.py --fetch-libs   # only fetch the sources jars of FETCHED_LIBS from their Maven (the version
                                    #   pinned in gradle.properties), check each against its published sha256 and
                                    #   extract it -> ref-src/libs/<name>/ (no Gradle: strict dependency verification
                                    #   refuses the IDE's sources download, D-123)
  python tools/refsrc.py --data     # + vanilla data/minecraft (recipes, loot, tags, worldgen) from the client jar
                                    #   -> ref-src/vanilla-data/ (for vanilla_scope.py); runs the data generator for
                                    #   registries.json when Java and the server jar are available
  python tools/refsrc.py --mcmeta 26.3   # the same vanilla data without Java or a Gradle build: fetches
                                    #   misode/mcmeta's <version>-data-json and -summary tags from GitHub, plus the
                                    #   structure templates (NBT, from <version>-data), summarised in structure_palettes.json
  python tools/refsrc.py --mcmeta 26.4 --out ref-src/vanilla-data-26.4   # a port target, beside the current data
  python tools/refsrc.py --list     # show candidate jars without extracting

Run `./gradlew build` (or your IDE's Gradle sync) once first: ModDevGradle decompiles Minecraft and
produces the sources jar this script looks for. 26.x is unobfuscated, so these are Mojang's own names.
ref-src/ is gitignored and read-only for Claude (the PreToolUse hook blocks edits); regenerate it after
every version change. Never commit or redistribute its contents.
"""
from __future__ import annotations

import argparse
import datetime as dt
import gzip
import io
import json
import os
import shutil
import struct
import subprocess
import sys
import urllib.request
import zipfile
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
REF = ROOT / "ref-src"
SOURCE_PREFIXES = ("net/minecraft/", "com/mojang/", "net/neoforged/")
LIBS = {"pal": ("playeranim", "player-animation", "playeranimation"), "bendable_cuboids": ("bendable",),
        "azurelib": ("azurelib",), "geckolib": ("geckolib",), "smartbrainlib": ("smartbrainlib", "smart-brain"),
        "iris": ("iris",), "lambdynamiclights": ("lambdynamiclights",), "shouldersurfing": ("shouldersurfing",)}
# --fetch-libs: name -> (Maven repository, group, artifact with {mc} for minecraft_version, gradle.properties key).
FETCHED_LIBS = {"azurelib": ("https://maven.azuredoom.com/mods", "mod.azure.azurelib", "azurelib-neo-{mc}",
                             "azurelib_version")}


def props() -> dict[str, str]:
    out = {}
    gp = ROOT / "gradle.properties"
    if gp.exists():
        for line in gp.read_text(encoding="utf-8").splitlines():
            if "=" in line and not line.lstrip().startswith("#"):
                k, v = line.split("=", 1)
                out[k.strip()] = v.strip()
    return out


def search_roots() -> list[Path]:
    roots = [ROOT / "build", ROOT / ".gradle"]
    gh = Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle")) / "caches"
    roots.append(gh)
    return [r for r in roots if r.exists()]


def jars(pattern_any: tuple[str, ...], need: str = "") -> list[Path]:
    found = []
    for root in search_roots():
        for p in root.rglob("*.jar"):
            n = p.name.lower()
            if any(s in n for s in pattern_any) and (not need or need in n):
                found.append(p)
    return sorted(set(found), key=lambda p: p.stat().st_mtime, reverse=True)


def has_entries(jar: Path, prefix: str, suffix: str) -> bool:
    try:
        with zipfile.ZipFile(jar) as z:
            for n in z.namelist():
                if n.startswith(prefix) and n.endswith(suffix):
                    return True
    except (zipfile.BadZipFile, OSError):
        return False
    return False


def extract(jar: Path, dest: Path, prefixes: tuple[str, ...], suffixes: tuple[str, ...]) -> int:
    count = 0
    with zipfile.ZipFile(jar) as z:
        for info in z.infolist():
            n = info.filename
            if info.is_dir() or not n.startswith(prefixes) or not n.endswith(suffixes):
                continue
            target = dest / n
            target.parent.mkdir(parents=True, exist_ok=True)
            with z.open(info) as src, open(target, "wb") as out:
                shutil.copyfileobj(src, out)
            count += 1
    return count


# Datapack registries vanilla_scope.py reads (26.3 names: worldgen/feature, not configured_feature).
MCMETA_REGISTRIES = ["recipe", "loot_table", "advancement", "villager_trade", "trade_set", "enchantment", "timeline",
                     "tag/item", "tag/block", "tag/entity_type", "tag/potion", "tag/villager_trade", "tag/worldgen/biome",
                     "tag/worldgen/structure", "worldgen/biome", "worldgen/placed_feature", "worldgen/feature",
                     "worldgen/block_state_provider", "worldgen/material_rule", "worldgen/material_condition",
                     "worldgen/noise_settings", "worldgen/structure", "worldgen/structure_set", "worldgen/template_pool",
                     "worldgen/processor_list"]
MCMETA = "https://raw.githubusercontent.com/misode/mcmeta"

# Structure templates that Java picks by name, so no data file lists them (26.3: IglooPieces, ShipwreckPieces,
# OceanRuinPieces, WoodlandMansionPieces). Jigsaw structures and template features list theirs in the data.
LEGACY_TEMPLATES = (
    ["igloo/top", "igloo/middle", "igloo/bottom"]
    + [f"shipwreck/{k}_{p}{d}" for k in ("rightsideup", "sideways", "upsidedown") for p in ("backhalf", "fronthalf", "full")
       for d in ("", "_degraded")] + ["shipwreck/with_mast", "shipwreck/with_mast_degraded"]
    + [f"underwater_ruin/{k}_{i}" for k in ("brick", "cracked", "mossy", "warm") for i in range(1, 9)]
    + [f"underwater_ruin/big_{k}_{i}" for k in ("brick", "cracked", "mossy") for i in range(1, 4)]
    + [f"underwater_ruin/big_warm_{i}" for i in range(4, 8)]
    + [f"woodland_mansion/{n}" for n in (
        [f"1x1_a{i}" for i in range(1, 6)] + [f"1x1_as{i}" for i in range(1, 5)] + [f"1x1_b{i}" for i in range(1, 6)]
        + [f"1x2_a{i}" for i in range(1, 10)] + [f"1x2_b{i}" for i in range(1, 6)] + [f"1x2_c{i}" for i in range(1, 5)]
        + ["1x2_c_stairs"] + [f"1x2_d{i}" for i in range(1, 6)] + ["1x2_d_stairs", "1x2_s1", "1x2_s2", "1x2_se1"]
        + [f"2x2_a{i}" for i in range(1, 5)] + [f"2x2_b{i}" for i in range(1, 6)] + ["2x2_s1"]
        + ["carpet_east", "carpet_north", "carpet_south_1", "carpet_south_2", "carpet_west_1", "carpet_west_2",
           "corridor_floor", "entrance", "indoors_door_1", "indoors_door_2", "indoors_wall_1", "indoors_wall_2", "roof",
           "roof_corner", "roof_front", "roof_inner_corner", "small_wall", "small_wall_corner", "wall_corner", "wall_flat",
           "wall_window"])])


def read_nbt(data: bytes):
    """A gzipped NBT file -> nested dicts and lists (just enough for structure templates)."""
    f = io.BytesIO(gzip.decompress(data))

    def rd(n):
        return f.read(n)

    def string():
        (n,) = struct.unpack(">H", rd(2))
        return rd(n).decode("utf-8", "replace")

    def payload(t):
        if t == 1:
            return struct.unpack(">b", rd(1))[0]
        if t == 2:
            return struct.unpack(">h", rd(2))[0]
        if t == 3:
            return struct.unpack(">i", rd(4))[0]
        if t == 4:
            return struct.unpack(">q", rd(8))[0]
        if t == 5:
            return struct.unpack(">f", rd(4))[0]
        if t == 6:
            return struct.unpack(">d", rd(8))[0]
        if t == 7:
            (n,) = struct.unpack(">i", rd(4))
            return rd(n)
        if t == 8:
            return string()
        if t == 9:
            et = rd(1)[0]
            (n,) = struct.unpack(">i", rd(4))
            return [payload(et) for _ in range(n)]
        if t == 10:
            out = {}
            while True:
                tt = rd(1)[0]
                if tt == 0:
                    return out
                name = string()                  # read the name first: `out[string()] = payload(tt)` would read the payload first
                out[name] = payload(tt)
        if t == 11:
            (n,) = struct.unpack(">i", rd(4))
            return list(struct.unpack(f">{n}i", rd(4 * n)))
        if t == 12:
            (n,) = struct.unpack(">i", rd(4))
            return list(struct.unpack(f">{n}q", rd(8 * n)))
        raise ValueError(f"bad NBT tag {t}")

    t = rd(1)[0]
    string()
    return payload(t)


def template_summary(nbt: dict) -> dict:
    """Blocks a template places (every palette), what its jigsaw blocks turn into, the pools those jigsaws pull from
    (so a structure's pieces can be followed exactly) and the entities it holds."""
    blocks = set()
    for pal in nbt.get("palettes") or [nbt.get("palette", [])]:
        for st in pal:
            name = st.get("Name") or st.get("id")
            if name:
                blocks.add(name)
    final, pools = set(), set()
    for b in nbt.get("blocks", []):
        tag = b.get("nbt") or {}
        fs = tag.get("final_state")
        if isinstance(fs, str):
            final.add(fs.split("[")[0].split("{")[0])
        pool = tag.get("pool")
        if isinstance(pool, str) and pool and pool != "minecraft:empty":
            pools.add(pool)
    ents = {e["nbt"]["id"] for e in nbt.get("entities", []) if isinstance(e.get("nbt"), dict) and e["nbt"].get("id")}
    return {"blocks": sorted(blocks), "final": sorted(final), "pools": sorted(pools), "entities": sorted(ents)}


def template_ids(dest: Path) -> list[str]:
    """Every structure template the fetched data names: template-pool elements (minus families that only Nether or End
    structures use), template and fossil features, and LEGACY_TEMPLATES."""
    d = dest / "data" / "minecraft"
    load = lambda p: json.loads(p.read_text(encoding="utf-8"))
    tag = lambda name: {v for v in (load(d / "tags" / "worldgen" / "biome" / f"{name}.json").get("values", [])
                                    if (d / "tags" / "worldgen" / "biome" / f"{name}.json").exists() else [])}
    elsewhere = tag("is_nether") | tag("is_end")
    fams, skip = set(), set()
    for p in (d / "worldgen" / "structure").glob("*.json"):
        j = load(p)
        start = j.get("start_pool")
        if not isinstance(start, str):
            continue
        fam = start.split(":")[-1].split("/")[0]
        b = j.get("biomes")
        tagfile = d / "tags" / "worldgen" / "biome" / (b[1:].split(":")[-1] + ".json") if isinstance(b, str) and b.startswith("#") else None
        biomes = set(load(tagfile).get("values", [])) if tagfile and tagfile.exists() else set()
        (skip if biomes and biomes <= elsewhere else fams).add(fam)
    ids = set(LEGACY_TEMPLATES)

    def walk(node):
        if isinstance(node, dict):
            loc = node.get("location")
            if isinstance(loc, str) and str(node.get("element_type", "")).endswith("pool_element"):
                ids.add(loc.split(":")[-1])
            if node.get("type") == "minecraft:template":
                for t in node.get("templates", []):
                    tid = ((t or {}).get("data") or {}).get("id") if isinstance(t, dict) else None
                    if isinstance(tid, str):
                        ids.add(tid.split(":")[-1])
            for k in ("fossil_structures", "overlay_structures"):
                for x in node.get(k, []) if isinstance(node.get(k), list) else []:
                    ids.add(x.split(":")[-1])
            for v in node.values():
                walk(v)
        elif isinstance(node, list):
            for x in node:
                walk(x)

    pools = d / "worldgen" / "template_pool"
    for p in pools.rglob("*.json") if pools.is_dir() else []:
        fam = p.relative_to(pools).parts[0]
        if fam in skip and fam not in fams:
            continue
        walk(load(p))
    for sub in ("feature", "placed_feature"):
        for p in (d / "worldgen" / sub).rglob("*.json") if (d / "worldgen" / sub).is_dir() else []:
            walk(load(p))
    return sorted(ids)


def write_structure_summary(dest: Path) -> int:
    root = dest / "data" / "minecraft" / "structure"
    out = {}
    for p in sorted(root.rglob("*.nbt")) if root.is_dir() else []:
        try:
            out[p.relative_to(root).with_suffix("").as_posix()] = template_summary(read_nbt(p.read_bytes()))
        except (OSError, ValueError, struct.error, EOFError, gzip.BadGzipFile) as e:
            print(f"refsrc: skipping template {p.name}: {e}", file=sys.stderr)
    (dest / "structure_palettes.json").write_bytes((json.dumps({"templates": out}, indent=0, sort_keys=True) + "\n").encode("utf-8"))  # LF on every OS
    return len(out)


def fetch(url: str, dest: Path, tries: int = 4) -> bool:
    if dest.exists() and dest.stat().st_size > 0:       # resumable: a rerun only fetches what's missing
        return True
    dest.parent.mkdir(parents=True, exist_ok=True)
    for _attempt in range(tries):
        try:
            with urllib.request.urlopen(url, timeout=20) as r:
                data = r.read()
            tmp = dest.with_suffix(dest.suffix + ".part")
            tmp.write_bytes(data)
            tmp.replace(dest)
            return True
        except OSError:
            continue
    return False


def mcmeta(version: str, dest: Path | None = None) -> int:
    """Fetch vanilla data for one version from misode/mcmeta (Mojang's data, extracted per release).

    Resumable for the same version. Files are skipped when present, so a folder holding another version's data
    (or data extracted from a client jar) is cleared first; otherwise old files would silently survive."""
    dest = dest or REF / "vanilla-data"
    marker = f"source=mcmeta {version}\n"
    have = ""
    for name in ("VERSION", ".fetching"):
        f = dest / name
        if f.exists():
            have = f.read_text(encoding="utf-8").splitlines()[0] + "\n"
            break
    if dest.exists() and any(dest.iterdir()) and have != marker:
        print(f"refsrc: {dest} holds {have.strip() or 'unknown data'}; clearing it for mcmeta {version}", flush=True)
        shutil.rmtree(dest)
    dest.mkdir(parents=True, exist_ok=True)
    (dest / ".fetching").write_text(marker, encoding="utf-8")
    reg_file = dest / "registries.json"
    if not fetch(f"{MCMETA}/{version}-summary/registries/data.json", reg_file):
        print(f"refsrc: could not fetch the {version} registry summary (is the version right? try 26.3)", file=sys.stderr)
        return 1
    fetch(f"{MCMETA}/{version}-summary/item_components/data.json", dest / "item_components.json")
    reg = json.loads(reg_file.read_text(encoding="utf-8"))
    jobs = []
    for key in MCMETA_REGISTRIES:
        folder = key.replace("tag/", "tags/", 1)
        for entry in reg.get(key, []):
            name = entry.split(":")[-1]
            jobs.append((f"{MCMETA}/{version}-data-json/data/minecraft/{folder}/{name}.json",
                         dest / "data" / "minecraft" / folder / f"{name}.json"))
    shown = dest.relative_to(ROOT) if dest.is_relative_to(ROOT) else dest
    print(f"refsrc: fetching {len(jobs)} vanilla data files for {version} into {shown} ...", flush=True)
    ok = []
    with ThreadPoolExecutor(max_workers=24) as pool:
        for i, good in enumerate(pool.map(lambda j: fetch(*j), jobs), 1):
            ok.append(good)
            if i % 1000 == 0:
                print(f"refsrc:   {i}/{len(jobs)}", flush=True)
    failed = [j[0] for j, good in zip(jobs, ok, strict=True) if not good]
    # Structure templates are NBT, which data-json leaves out; the <version>-data tag serves them.
    names = template_ids(dest)
    tjobs = [(f"{MCMETA}/{version}-data/data/minecraft/structure/{n}.nbt", dest / "data" / "minecraft" / "structure" / f"{n}.nbt")
             for n in names]
    print(f"refsrc: fetching {len(tjobs)} structure templates ...", flush=True)
    with ThreadPoolExecutor(max_workers=24) as pool:
        tok = list(pool.map(lambda j: fetch(*j), tjobs))
    tmissing = [j[0].rsplit("/structure/", 1)[1] for j, good in zip(tjobs, tok, strict=True) if not good]
    n = write_structure_summary(dest)
    (dest / "VERSION").write_text(f"source=mcmeta {version}\nfetched={dt.date.today().isoformat()}\nfiles={len(jobs)}\n"
                                  f"failed={len(failed)}\ntemplates={n}\ntemplates_missing={len(tmissing)}\n", encoding="utf-8")
    (dest / ".fetching").unlink(missing_ok=True)
    print(f"refsrc: {len(jobs) - len(failed)} files fetched, {len(failed)} failed" + (f" (first: {failed[0]})" if failed else "")
          + f"; {n} structure templates summarised in structure_palettes.json"
          + (f" ({len(tmissing)} names not found, e.g. {tmissing[0]})" if tmissing else ""))
    return 1 if failed else 0


def vanilla_data(client: Path | None) -> int:
    """Extract data/minecraft from the client jar and, if possible, run the data generator for registries.json."""
    if not client:
        print("refsrc: no client jar with data/minecraft found; run ./gradlew runClient once, or use --mcmeta 26.3",
              file=sys.stderr)
        return 1
    dest = REF / "vanilla-data"
    if dest.exists():
        shutil.rmtree(dest)
    n = extract(client, dest, ("data/minecraft/",), (".json", ".nbt"))
    t = write_structure_summary(dest)
    print(f"refsrc: {n} vanilla data files from {client.name} -> {dest.relative_to(ROOT)} ({t} structure templates summarised)")
    server = next((j for j in jars(("server",)) if has_entries(j, "META-INF/versions", ".jar") or "server" in j.name.lower()), None)
    java = shutil.which("java")
    if server and java:
        out = dest / "generated"
        cmd = [java, "-DbundlerMainClass=net.minecraft.data.Main", "-jar", str(server), "--reports", "--output", str(out)]
        r = subprocess.run(cmd, cwd=dest, capture_output=True, text=True)
        rep = out / "reports" / "registries.json"
        if r.returncode == 0 and rep.exists():
            (dest / "reports").mkdir(exist_ok=True)
            shutil.copy(rep, dest / "reports" / "registries.json")
            items = out / "reports" / "items.json"
            if items.exists():
                shutil.copy(items, dest / "reports" / "items.json")
            print("refsrc: registries and item components from the vanilla data generator")
            return 0
    print("refsrc: no registries report (needs Java 25 and the server jar); fetch the summary with --mcmeta <version> "
          "or put registries.json next to data/", file=sys.stderr)
    return 1


def fetch_libs(p: dict[str, str]) -> int:
    """Each FETCHED_LIBS sources jar at its pinned version, checked against the sha256 its Maven publishes beside it,
    extracted to ref-src/libs/<name>/ with a VERSION file naming the artifact and the hash. A mismatch extracts
    nothing and fails."""
    import hashlib
    mc = p.get("minecraft_version", "?")
    bad = 0
    for name, (repo, group, artifact, key) in FETCHED_LIBS.items():
        version = p.get(key)
        if not version:
            print(f"refsrc: {name}: gradle.properties has no {key}", file=sys.stderr)
            bad += 1
            continue
        art = artifact.format(mc=mc)
        jar_name = f"{art}-{version}-sources.jar"
        url = f"{repo}/{group.replace('.', '/')}/{art}/{version}/{jar_name}"
        try:
            with urllib.request.urlopen(url, timeout=60) as r:
                data = r.read()
            with urllib.request.urlopen(url + ".sha256", timeout=20) as r:
                published = r.read().decode("ascii", "replace").split()[0].strip().lower()
        except OSError as e:
            print(f"refsrc: {name}: could not fetch {url}: {e}", file=sys.stderr)
            bad += 1
            continue
        got = hashlib.sha256(data).hexdigest()
        if got != published:
            print(f"refsrc: {name}: {jar_name} hashes {got}, its Maven says {published}; nothing extracted",
                  file=sys.stderr)
            bad += 1
            continue
        ldest = REF / "libs" / name
        if ldest.exists():
            shutil.rmtree(ldest)
        ldest.mkdir(parents=True)
        tmp = ldest / jar_name
        tmp.write_bytes(data)
        k = extract(tmp, ldest, ("",), (".java", ".json"))
        tmp.unlink()
        (ldest / "VERSION").write_text(f"artifact={group}:{art}:{version}\nfrom={repo}\nsources_sha256={got}\n"
                                       f"extracted={dt.date.today().isoformat()}\n", encoding="utf-8", newline="\n")
        print(f"refsrc: {k} files for {name} from {jar_name} (sha256 {got[:12]}..., as published)")
    return 1 if bad else 0


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description="Extract reference sources into ref-src/.")
    ap.add_argument("--assets", action="store_true", help="also extract vanilla assets/minecraft into ref-src/vanilla/")
    ap.add_argument("--libs", action="store_true", help="also extract sources of pinned libraries into ref-src/libs/")
    ap.add_argument("--data", action="store_true", help="also extract vanilla data/minecraft into ref-src/vanilla-data/")
    ap.add_argument("--mcmeta", metavar="VERSION", help="only fetch vanilla data for VERSION from misode/mcmeta (no Java needed)")
    ap.add_argument("--out", metavar="DIR", help="with --mcmeta: fetch into DIR instead of ref-src/vanilla-data/ "
                    "(for /port-plan: compare a target version without touching the current data)")
    ap.add_argument("--fetch-libs", action="store_true",
                    help="only fetch FETCHED_LIBS' sources jars from their Maven, sha256-checked, into ref-src/libs/")
    ap.add_argument("--list", action="store_true", help="only list candidate jars")
    a = ap.parse_args(argv)
    if a.mcmeta:
        return mcmeta(a.mcmeta, Path(a.out).resolve() if a.out else None)
    p = props()
    if a.fetch_libs:
        return fetch_libs(p)
    mc, neo = p.get("minecraft_version", "?"), p.get("neo_version") or p.get("neoforge_version", "?")
    print(f"refsrc: minecraft {mc}, neoforge {neo}; searching {', '.join(str(r) for r in search_roots()) or 'nothing'}")

    src_candidates = [j for j in jars(("sources",)) if ("neoforge" in j.name.lower() or "minecraft" in j.name.lower())]
    if neo != "?":
        src_candidates.sort(key=lambda j: (neo not in j.name, -j.stat().st_mtime))
    if a.list:
        for j in src_candidates[:20]:
            print("  sources:", j)
    chosen = next((j for j in src_candidates if has_entries(j, "net/minecraft/", ".java")), None)
    if not chosen:
        print("refsrc: no sources jar with net/minecraft/*.java found. Run ./gradlew build (or the IDE sync) once so "
              "ModDevGradle decompiles Minecraft, then re-run.", file=sys.stderr)
        return 1
    if a.list:
        print("  would use:", chosen)
        return 0

    dest = REF / "minecraft"
    if dest.exists():
        shutil.rmtree(dest)
    n = extract(chosen, dest, SOURCE_PREFIXES, (".java",))
    print(f"refsrc: {n} source files from {chosen.name} -> {dest.relative_to(ROOT)}")
    # ModDevGradle 2 splits the sources: the jar above holds the patched Minecraft classes, and NeoForge's own API
    # (events, loot modifiers, conditions, registries, GameTest hooks) ships in neoforge-<version>-sources.jar.
    # A ref-src/ without NeoForge's API is worse than none: "not in ref-src/" means "doesn't exist" (rules/java.md), so
    # a missing jar is a failure (exit 1, and VERSION says MISSING), not a warning.
    neo_jar, neo_missing = None, False
    if not has_entries(chosen, "net/neoforged/neoforge/", ".java"):
        neo_jar = next((j for j in src_candidates if neo != "?" and j.name.lower() == f"neoforge-{neo}-sources.jar".lower()), None)
        if neo_jar:
            m = extract(neo_jar, dest, ("net/neoforged/",), (".java",))
            print(f"refsrc: {m} NeoForge source files from {neo_jar.name} -> {dest.relative_to(ROOT)}")
        else:
            neo_missing = True
            what = f"neoforge-{neo}-sources.jar" if neo != "?" else "a NeoForge sources jar (gradle.properties has no neo_version)"
            print(f"refsrc: no {what} found, so NeoForge's own API isn't in ref-src/; run ./gradlew build or the IDE "
                  "sync, which downloads it, then rerun", file=sys.stderr)

    if a.assets:
        client = next((j for j in jars(("client", "minecraft", "neoforge")) if has_entries(j, "assets/minecraft/textures/", ".png")), None)
        if client:
            vdest = REF / "vanilla"
            if vdest.exists():
                shutil.rmtree(vdest)
            m = extract(client, vdest, ("assets/minecraft/",), (".png", ".json", ".mcmeta"))
            print(f"refsrc: {m} vanilla asset files from {client.name} -> {vdest.relative_to(ROOT)}")
        else:
            print("refsrc: no jar with vanilla textures found (run the client once: ./gradlew runClient)", file=sys.stderr)

    if a.data:
        client = next((j for j in jars(("client", "minecraft", "neoforge")) if has_entries(j, "data/minecraft/recipe/", ".json")), None)
        vanilla_data(client)

    if a.libs:
        for name, keys in LIBS.items():
            lib = next((j for j in jars(keys, "sources")), None)
            if not lib:
                print(f"refsrc: no sources jar for {name} in the Gradle cache (add it as a dependency, then sync)")
                continue
            ldest = REF / "libs" / name
            if ldest.exists():
                shutil.rmtree(ldest)
            k = extract(lib, ldest, ("",), (".java",))
            print(f"refsrc: {k} files for {name} from {lib.name}")

    REF.mkdir(exist_ok=True)
    (REF / "VERSION").write_text(
        f"minecraft={mc}\nneoforge={neo}\nsources_jar={chosen}\n"
        + (f"neoforge_sources_jar={neo_jar}\n" if neo_jar else "neoforge_sources_jar=MISSING\n" if neo_missing else "")
        + f"extracted={dt.date.today().isoformat()}\n", encoding="utf-8")
    if neo_missing:
        return 1
    print("refsrc: done. Search with: rg -n \"class PostChain\" ref-src/minecraft")
    return 0


if __name__ == "__main__":
    for _stream in (sys.stdout, sys.stderr):   # Windows pipes default to the ANSI code page; names and kanji need UTF-8
        if hasattr(_stream, "reconfigure"):
            _stream.reconfigure(encoding="utf-8", errors="backslashreplace")
    sys.exit(main())

#!/usr/bin/env python3
"""
audio_lint.py — keep every sound playable the way the audio bible intends.

Checks each .ogg under assets/<ns>/sounds/:
  * codec is Vorbis (Minecraft plays OGG Vorbis only)
  * positional sounds are MONO — stereo files never attenuate with distance, so a stereo sword
    clang is heard at full volume across the map. Stereo is allowed only for sounds.json entries
    with "stream": true (music, long ambience) or paths matched by stereo_globs.
  * sample rate 44.1 kHz or 48 kHz
  * optional --loudness: true peak (fails above the ceiling) and level: EBU R128 integrated loudness for
    sounds of 3 s or more, RMS level for short one-shots (integrated loudness is undefined below its
    400 ms gating window, so short SFX would all read -70 LUFS)
  * every sound file is referenced from sounds.json (orphans waste jar size), and every referenced
    file exists

Usage:
  python tools/audio_lint.py                 # fast checks
  python tools/audio_lint.py --loudness      # slower, adds loudness/peak
  python tools/audio_lint.py file.ogg ...    # specific files (used by the post-edit hook)
Needs ffprobe (and ffmpeg for --loudness). Exit 0 = clean, 1 = errors.
"""
from __future__ import annotations

import argparse
import fnmatch
import json
import re
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "src" / "main" / "resources" / "assets"
STEREO_GLOBS = ["*/sounds/music/*", "*/sounds/ambient/*"]
LOUDNESS_TARGET = (-30.0, -14.0)   # LUFS window for sounds >= 3 s that aren't streamed (PROPOSED; tune against vanilla)
RMS_TARGET = (-30.0, -12.0)        # dBFS RMS window for short one-shots (PROPOSED; tune against vanilla)
SHORT_S = 3.0
TRUE_PEAK_CEILING = -1.0           # dBTP


def probe(path: Path) -> dict:
    out = subprocess.run(
        ["ffprobe", "-v", "error", "-select_streams", "a:0", "-show_entries",
         "stream=codec_name,channels,sample_rate:format=duration", "-of", "json", str(path)],
        capture_output=True, text=True)
    if out.returncode:
        raise RuntimeError(out.stderr.strip() or "ffprobe failed")
    data = json.loads(out.stdout or "{}")
    st = (data.get("streams") or [{}])[0]
    return {"codec": st.get("codec_name"), "channels": int(st.get("channels", 0) or 0),
            "rate": int(st.get("sample_rate", 0) or 0),
            "duration": float((data.get("format") or {}).get("duration", 0) or 0)}


def loudness(path: Path) -> tuple[float | None, float | None]:
    out = subprocess.run(["ffmpeg", "-hide_banner", "-nostats", "-i", str(path),
                          "-af", "ebur128=peak=true", "-f", "null", "-"], capture_output=True, text=True)
    text = out.stderr
    i = re.findall(r"I:\s+(-?\d+(?:\.\d+)?) LUFS", text)
    p = re.findall(r"Peak:\s+(-?\d+(?:\.\d+)?) dBFS", text)
    return (float(i[-1]) if i else None, float(p[-1]) if p else None)


def rms_level(path: Path) -> float | None:
    out = subprocess.run(["ffmpeg", "-hide_banner", "-nostats", "-i", str(path), "-af", "volumedetect",
                          "-f", "null", "-"], capture_output=True, text=True)
    m = re.findall(r"mean_volume:\s+(-?\d+(?:\.\d+)?) dB", out.stderr)
    return float(m[-1]) if m else None


def load_sound_index(ns_dir: Path) -> tuple[dict[str, bool], list[str]]:
    """Map 'sounds/<path>.ogg' -> stream flag, plus problems found in sounds.json."""
    idx: dict[str, bool] = {}
    problems: list[str] = []
    sj = ns_dir / "sounds.json"
    if not sj.exists():
        return idx, problems
    try:
        data = json.loads(sj.read_text(encoding="utf-8"))
    except ValueError as e:
        return idx, [f"{sj}: invalid JSON ({e})"]
    for event, spec in data.items():
        for s in spec.get("sounds", []):
            if isinstance(s, str):
                name, stream, typ = s, False, "file"
            else:
                name, stream, typ = s.get("name", ""), bool(s.get("stream", False)), s.get("type", "file")
            if typ != "file":
                continue
            ns, _, rel = name.partition(":") if ":" in name else (ns_dir.name, "", name)
            if ns != ns_dir.name:
                continue
            key = f"sounds/{rel}.ogg"
            idx[key] = idx.get(key, False) or stream
            if not (ns_dir / key).exists():
                problems.append(f"sounds.json event '{event}' points to missing file {ns}:{rel}")
    return idx, problems


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description="Lint mod audio.")
    ap.add_argument("paths", nargs="*")
    ap.add_argument("--loudness", action="store_true")
    ap.add_argument("--json", action="store_true")
    a = ap.parse_args(argv)
    if not shutil.which("ffprobe"):
        print("audio_lint: ffprobe not on PATH — skipping (install ffmpeg)", file=sys.stderr)
        return 0

    files = [Path(p) for p in a.paths if p.endswith(".ogg")] if a.paths else sorted(ASSETS.rglob("sounds/**/*.ogg"))
    indexes: dict[Path, dict] = {}
    errors, warnings = [], []
    for ns_dir in (ASSETS.iterdir() if ASSETS.exists() else []):
        if ns_dir.is_dir():
            idx, probs = load_sound_index(ns_dir)
            indexes[ns_dir.resolve()] = idx
            if not a.paths:
                errors.extend(probs)

    for f in files:
        try:
            rel_ns = f.resolve().relative_to(ASSETS.resolve()).as_posix()
        except ValueError:
            rel_ns = f.as_posix()
        ns = rel_ns.split("/", 1)[0]
        rel = rel_ns.split("/", 1)[1] if "/" in rel_ns else rel_ns
        idx = indexes.get((ASSETS / ns).resolve(), {})
        try:
            info = probe(f)
        except RuntimeError as e:
            errors.append(f"{rel_ns}: {e}")
            continue
        if info["codec"] != "vorbis":
            errors.append(f"{rel_ns}: codec {info['codec']} — Minecraft needs OGG Vorbis")
        stream = idx.get(rel, False)
        stereo_ok = stream or any(fnmatch.fnmatch(rel_ns, g) for g in STEREO_GLOBS)
        if info["channels"] > 1 and not stereo_ok:
            errors.append(f"{rel_ns}: {info['channels']} channels — positional sounds must be mono "
                          f"(stereo never attenuates with distance)")
        if info["rate"] not in (44100, 48000):
            warnings.append(f"{rel_ns}: sample rate {info['rate']} Hz (use 44.1 or 48 kHz)")
        if info["duration"] > 20 and not stream:
            warnings.append(f"{rel_ns}: {info['duration']:.0f}s long but not streamed — set \"stream\": true in sounds.json")
        if idx and rel not in idx and not a.paths:
            warnings.append(f"{rel_ns}: not referenced by sounds.json (orphan)")
        if a.loudness:
            lufs, peak = loudness(f)
            if not stream and (info["duration"] < SHORT_S or lufs is None or lufs <= -69.0):
                rms = rms_level(f)
                if rms is not None and not (RMS_TARGET[0] <= rms <= RMS_TARGET[1]):
                    warnings.append(f"{rel_ns}: RMS level {rms:.1f} dBFS outside {RMS_TARGET} for a short one-shot (PROPOSED target)")
            elif lufs is not None and not stream and not (LOUDNESS_TARGET[0] <= lufs <= LOUDNESS_TARGET[1]):
                warnings.append(f"{rel_ns}: integrated loudness {lufs:.1f} LUFS outside {LOUDNESS_TARGET} (PROPOSED target)")
            if peak is not None and peak > TRUE_PEAK_CEILING:
                errors.append(f"{rel_ns}: true peak {peak:.1f} dBTP above {TRUE_PEAK_CEILING} dBTP ceiling")

    if a.json:
        print(json.dumps({"files": len(files), "errors": errors, "warnings": warnings}, indent=2))
    else:
        for e in errors:
            print("ERROR", e)
        for w in warnings:
            print("warn ", w)
        print(f"audio_lint: {len(files)} file(s), {len(errors)} error(s), {len(warnings)} warning(s)")
    return 1 if errors else 0


if __name__ == "__main__":
    for _stream in (sys.stdout, sys.stderr):   # Windows pipes default to the ANSI code page; names and kanji need UTF-8
        if hasattr(_stream, "reconfigure"):
            _stream.reconfigure(encoding="utf-8", errors="backslashreplace")
    sys.exit(main())

#!/usr/bin/env python3
"""
make_test_floor.py: write kimetsu:test_floor and kimetsu:test_cells, the empty structures the combat GameTests stand on
(D-087).

A GameTest starts only once every chunk its structure's box touches ticks entities (GameTestInfo.tick), and the
runner force-loads exactly those chunks. Vanilla's minecraft:empty is 1x1x1, so a fight's 9x9 floor could reach
into a chunk that didn't tick entities yet: on a loaded machine a dummy there stood still through its push and
lived it late or never (the push flakes). This structure is vanilla's empty.nbt (one air block, 26.3's data
version) with a 16x4x16 box: the floor and room past it, so the barrier walls the runner builds around the box
stand clear of every push. kimetsu:test_cells is the same at 51x4x21: ten walled 9x9 cells, five by two, for
ten fights side by side (ten_fights_in_budget).

  python tools/make_test_floor.py            # (re)write both structures
  python tools/make_test_floor.py --check    # exit 1 if either file isn't what this tool writes
  python tools/make_test_floor.py --size 1 1 1 --out empty.nbt   # the same NBT as vanilla's empty.nbt
"""
from __future__ import annotations

import argparse
import gzip
import struct
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DIR = ROOT / "src" / "main" / "resources" / "data" / "kimetsu" / "structure"
STRUCTURES = {DIR / "test_floor.nbt": (16, 4, 16), DIR / "test_cells.nbt": (51, 4, 21)}
DATA_VERSION = 5023  # 26.3's world_version (version.json), as vanilla's empty.nbt carries it

END, INT, STRING, LIST, COMPOUND = 0, 3, 8, 9, 10


def name(s: str) -> bytes:
    b = s.encode("utf-8")
    return struct.pack(">H", len(b)) + b


def ints(*values: int) -> bytes:
    return bytes([INT]) + struct.pack(">i", len(values)) + struct.pack(">%di" % len(values), *values)


def structure(size: tuple[int, int, int]) -> bytes:
    """The uncompressed NBT: vanilla's empty.nbt with its size, keys in vanilla's order."""
    block = bytes([LIST]) + name("pos") + ints(0, 0, 0) + bytes([INT]) + name("state") + struct.pack(">i", 0)
    air = bytes([STRING]) + name("id") + name("minecraft:air")
    return (bytes([COMPOUND]) + name("")
            + bytes([LIST]) + name("size") + ints(*size)
            + bytes([LIST]) + name("entities") + bytes([END]) + struct.pack(">i", 0)
            + bytes([LIST]) + name("blocks") + bytes([COMPOUND]) + struct.pack(">i", 1) + block + bytes([END])
            + bytes([LIST]) + name("palette") + bytes([COMPOUND]) + struct.pack(">i", 1) + air + bytes([END])
            + bytes([INT]) + name("DataVersion") + struct.pack(">i", DATA_VERSION)
            + bytes([END]))


def write(out: Path, size: tuple[int, int, int], check: bool) -> int:
    want = structure(size)
    if check:
        try:
            have = gzip.decompress(out.read_bytes())
        except (OSError, EOFError) as e:
            print(f"FAIL {out}: {e}")
            return 1
        if have != want:
            print(f"FAIL {out} isn't the {list(size)} structure this tool writes: run it without --check")
            return 1
        print(f"OK {out}: {size[0]}x{size[1]}x{size[2]}")
        return 0
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_bytes(gzip.compress(want, mtime=0))
    print(f"wrote {out}: {size[0]}x{size[1]}x{size[2]}, {len(want)} bytes of NBT")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--size", type=int, nargs=3, metavar=("X", "Y", "Z"), help="one structure of this size (with --out)")
    ap.add_argument("--out", type=Path, help="where that one structure goes")
    ap.add_argument("--check", action="store_true", help="compare instead of writing")
    args = ap.parse_args()
    if (args.size is None) != (args.out is None):
        ap.error("--size and --out go together")
    todo = {args.out: tuple(args.size)} if args.out is not None else STRUCTURES
    failed = 0
    for out, size in todo.items():
        failed |= write(out, size, args.check)
    return failed


if __name__ == "__main__":
    sys.exit(main())

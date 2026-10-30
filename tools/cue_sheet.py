"""Draw the world cues' sprite sheet, textures/cue/cues.png (slice 5d-4a, D-144, D-145's A3, D-146).

One 128x64 sheet on art/palettes/cue-reserved.gpl, each cell outlined in the reserved dark outline and kept one
texel clear of its edge, so the UV table's half-texel inset (client.kfx.CueSheet) never cuts an outline:

    (0,0)   glint        16x16  four-point star (white): deflect
    (16,0)  glint_flare  16x16  the star brightened, with diagonal sparkle (D-134 (4): its last half-beat)
    (32,0)  shatter      4 x 16x16 strip: the star cracks, splits, flies apart in chips, then specks (a feint's break)
    (96,0)  burst        16x16  jagged splat with eight uneven spikes (red): dodge
    (112,0) ring         16x16  the ring's band across v (purple): a grab; its rows hug the cell's top, the reach
    (0,16)  neck         4 x 16x16 strip: the Thread's thin line across v, its shimmer stepping along u (gold)
    (64,16) hollow       16x16  a hollow ring 12 texels across (purple): a grab's point beside its ring (D-148)
    (0,32)  clash        32x32  eight-point burst (white): the micro-clash's flash

Each shape reads without colour: a plus, a plus with sparkle, a splat, a ring, a line, an O, an eight-rayed star.
Cues are screen-sized billboards at 32 texels a block, not world materials (D-145's A12, art bible section 6). A
band's quad is cue_size thick, so its painted rows set how thick it reads: 6 of 16 (the ring, 0.19 blocks at cue_size
0.5; the Thread, 6/16 of the neck's length).

    python tools/cue_sheet.py                  # write the sheet
    python tools/cue_sheet.py --placeholder    # outlined squares in each cell (build step 1's smoke)
    python tools/cue_sheet.py --preview out.png  # also an 8x preview
"""

from __future__ import annotations

import argparse
import math
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
SHEET = ROOT / "src/main/resources/assets/kimetsu/textures/cue/cues.png"

WHITE = (244, 250, 255, 255)
RED = (255, 43, 43, 255)
PURPLE = (176, 76, 255, 255)
GOLD = (255, 210, 60, 255)
OUTLINE = (10, 10, 12, 255)
SHIMMER = WHITE  # the Thread's moving highlight: reserved white on the gold (art review: no colour off the palette)
CLEAR = (0, 0, 0, 0)

W, H = 128, 64

# The red splat, by hand: an uneven body with eight one-texel spikes, none along the glint's axes alone.
BURST = [
    "................",
    "................",
    "..#.....#.......",
    "...#....#...##..",
    "....#..###.#....",
    ".....######.....",
    "....#######.....",
    "..#########.....",
    ".....#########..",
    ".....#######....",
    "....#######.....",
    "...#..#...##....",
    "..#...#.....#...",
    "......#......#..",
    "................",
    "................",
]

# The shatter's chips (frame 2) and specks (frame 3), each spinning on the way out the same way round: the top
# piece drifts left, the left one down, the bottom one right, the right one up.
CHIPS = [(5, 2), (6, 2), (5, 3), (2, 10), (2, 11), (3, 11), (10, 13), (11, 13), (11, 12), (13, 4), (13, 5), (12, 4)]
SPECKS = [(3, 4), (4, 3), (3, 11), (4, 12), (11, 12), (12, 11), (11, 3), (12, 4)]


def outlined(mask: set[tuple[int, int]], size: int) -> set[tuple[int, int]]:
    """The 8-neighbour ring around a mask, inside a size x size cell."""
    ring = set()
    for x, y in mask:
        for dx in (-1, 0, 1):
            for dy in (-1, 0, 1):
                p = (x + dx, y + dy)
                if p not in mask and 0 <= p[0] < size and 0 <= p[1] < size:
                    ring.add(p)
    return ring


def paint(img: Image.Image, ox: int, oy: int, mask: set[tuple[int, int]], fill, size: int = 16) -> None:
    """The mask in its colour with the dark outline round it, at the cell whose corner is (ox, oy)."""
    for x, y in outlined(mask, size):
        img.putpixel((ox + x, oy + y), OUTLINE)
    for x, y in mask:
        img.putpixel((ox + x, oy + y), fill)


def grid(rows: list[str]) -> set[tuple[int, int]]:
    """A hand-drawn mask: '#' is filled."""
    return {(x, y) for y, row in enumerate(rows) for x, ch in enumerate(row) if ch == "#"}


def star(radius: float, core: float, size: int = 16) -> set[tuple[int, int]]:
    """A four-point star about the cell's centre: rays along x and y, half-width core at the centre, 0.5 at the tips."""
    c = size / 2
    mask = set()
    for y in range(size):
        for x in range(size):
            dx, dy = abs(x + 0.5 - c), abs(y + 0.5 - c)
            for along, across in ((dx, dy), (dy, dx)):
                if along <= radius and across <= 0.5 + (core - 0.5) * (1 - along / radius):
                    mask.add((x, y))
    return mask


def sparkle(reach: int) -> set[tuple[int, int]]:
    """One-texel rays along both diagonals, from the centre out to reach texels."""
    return {(7 - k, 7 - k) for k in range(reach)} | {(8 + k, 8 + k) for k in range(reach)} | {
        (8 + k, 7 - k) for k in range(reach)
    } | {(7 - k, 8 + k) for k in range(reach)}


def flare() -> set[tuple[int, int]]:
    """The glint brightened: a fuller star to the cell's bounds, with sparkle on its diagonals."""
    return star(6.0, 2.5) | sparkle(4)


def cracked() -> tuple[set[tuple[int, int]], set[tuple[int, int]]]:
    """The glint with a crack across its centre (shatter frame 0): the star, and the crack drawn over it in outline."""
    return star(5.5, 2.0), {(9, 6), (8, 7), (7, 8), (6, 9)}


def split() -> set[tuple[int, int]]:
    """The star's two halves either side of the crack, pushed a texel apart (shatter frame 1)."""
    s = star(4.5, 2.0)
    return {(x - 1, y - 1) for x, y in s if x + y < 15} | {(x + 1, y + 1) for x, y in s if x + y > 15}


def hollow(outer: float = 6.0, inner: float = 4.0) -> set[tuple[int, int]]:
    """A ring about the cell's centre: texels whose centres lie inner to outer from it (12 across, 2 thick, an O)."""
    return {(x, y) for y in range(16) for x in range(16) if inner <= math.hypot(x + 0.5 - 8, y + 0.5 - 8) <= outer}


def clash(size: int) -> set[tuple[int, int]]:
    """Eight rays: the four along the axes longer than the diagonals."""
    c = size / 2
    mask = set()
    for y in range(size):
        for x in range(size):
            dx, dy = x + 0.5 - c, y + 0.5 - c
            r = math.hypot(dx, dy)
            for k in range(8):
                a = k * math.pi / 4
                length = 13.5 if k % 2 == 0 else 9.5
                along = dx * math.cos(a) + dy * math.sin(a)
                across = abs(-dx * math.sin(a) + dy * math.cos(a))
                if 0 <= along <= length and across <= 0.5 + 2.0 * (1 - along / length):
                    mask.add((x, y))
            if r <= 3.5:
                mask.add((x, y))
    return mask


def paint_band(img: Image.Image, ox: int, oy: int, top: int, bottom: int, colour) -> None:
    """A band of rows top to bottom across the cell, outlined above and below only (it repeats along u)."""
    for x in range(16):
        img.putpixel((ox + x, oy + top - 1), OUTLINE)
        img.putpixel((ox + x, oy + bottom + 1), OUTLINE)
        for y in range(top, bottom + 1):
            img.putpixel((ox + x, oy + y), colour(x) if callable(colour) else colour)


def draw(placeholder: bool) -> Image.Image:
    img = Image.new("RGBA", (W, H), CLEAR)
    if placeholder:
        square = {(x, y) for x in range(3, 13) for y in range(3, 13)}
        for i, colour in enumerate((WHITE, WHITE, WHITE, WHITE, WHITE, WHITE, RED, PURPLE)):
            paint(img, i * 16, 0, square, colour)
        for i in range(4):
            paint(img, i * 16, 16, square, GOLD)
        paint(img, 64, 16, square, PURPLE)
        paint(img, 0, 32, {(x, y) for x in range(4, 28) for y in range(4, 28)}, WHITE, 32)
        return img
    paint(img, 0, 0, star(5.5, 2.0), WHITE)
    paint(img, 16, 0, flare(), WHITE)
    body, crack = cracked()
    paint(img, 32, 0, body, WHITE)
    for x, y in crack:
        img.putpixel((32 + x, y), OUTLINE)
    paint(img, 48, 0, split(), WHITE)
    paint(img, 64, 0, set(CHIPS), WHITE)
    paint(img, 80, 0, set(SPECKS), WHITE)
    paint(img, 96, 0, grid(BURST), RED)
    paint_band(img, 112, 0, 2, 5, PURPLE)  # outline rows 1 and 6: the outer edge, the reach, at the top
    for f in range(4):  # outline rows 5 and 10: centred on the neck
        paint_band(img, 16 * f, 16, 6, 9, lambda x, f=f: SHIMMER if (x - 4 * f) % 16 in (0, 1) else GOLD)
    paint(img, 64, 16, hollow(), PURPLE)  # outlined in and out, its centre clear
    paint(img, 0, 32, clash(32), WHITE, 32)
    return img


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__.split("\n", 1)[0])
    p.add_argument("--placeholder", action="store_true", help="outlined squares in each cell")
    p.add_argument("--preview", type=Path, help="also write an 8x nearest-neighbour preview here")
    a = p.parse_args()
    img = draw(a.placeholder)
    SHEET.parent.mkdir(parents=True, exist_ok=True)
    img.save(SHEET)
    print(f"wrote {SHEET.relative_to(ROOT)} ({W}x{H}{', placeholder' if a.placeholder else ''})")
    if a.preview:
        img.resize((W * 8, H * 8), Image.NEAREST).save(a.preview)
        print(f"wrote {a.preview}")


if __name__ == "__main__":
    main()

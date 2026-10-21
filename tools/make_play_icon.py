#!/usr/bin/env python3
"""Render the Play Store's 512x512 launcher icon from the adaptive icon's geometry.

Google Play wants a 512x512 32-bit PNG, which Android's vector drawables can't produce on
their own. Rather than keep a hand-exported bitmap that quietly drifts from the shipped
icon, this redraws the SAME shapes declared in
`android/app/src/main/res/drawable/ic_launcher_foreground.xml` — coordinates below are in
that file's 108-unit viewport, so the two stay in step by construction. Change one, change
the other, and diff the output.

Stdlib only, in keeping with the rest of the project: shapes are exact signed-distance
functions, antialiased by taking coverage from the distance, and the PNG is assembled with
`zlib` and `struct`.

    python3 tools/make_play_icon.py [-o docs/play/icon-512.png] [-s 512]
"""

from __future__ import annotations

import argparse
import pathlib
import struct
import zlib
from math import hypot

# ── palette (mirrors res/values/colors.xml) ──
BACKGROUND = (0xF4, 0xF1, 0xEA)
CASING = (0x2F, 0x3A, 0x45)
MERCURY = (0xE2, 0x55, 0x2F)

VIEWPORT = 108.0


def circle(cx: float, cy: float, r: float):
    """Signed distance to a circle: negative inside."""
    return lambda x, y: hypot(x - cx, y - cy) - r


def round_rect(cx: float, cy: float, w: float, h: float, r: float):
    """Signed distance to a rounded rectangle centred on (cx, cy)."""
    hw, hh = w / 2 - r, h / 2 - r

    def sdf(x, y):
        dx = abs(x - cx) - hw
        dy = abs(y - cy) - hh
        # Outside distance comes from the positive components; inside, from the least
        # negative one. Standard rounded-box SDF.
        return hypot(max(dx, 0.0), max(dy, 0.0)) + min(max(dx, dy), 0.0) - r

    return sdf


# Painted in order; later shapes sit on top. The stem's square bottom and the mercury
# column's lower end are covered by their bulbs, exactly as in the vector drawable.
SHAPES = [
    (round_rect(58, 50.5, 16, 51, 8), CASING),     # stem capsule, y 25..76
    (circle(58, 70, 14), CASING),                  # bulb
    (round_rect(58, 57, 8, 34, 4), MERCURY),       # mercury column, y 40..74
    (circle(58, 70, 9), MERCURY),                  # mercury bulb
    (round_rect(42, 34.5, 8, 3.4, 1.7), CASING),   # scale marks
    (round_rect(42, 45.5, 8, 3.4, 1.7), CASING),
    (round_rect(42, 56.5, 8, 3.4, 1.7), CASING),
]


def render(size: int) -> bytearray:
    """Draw the icon into a size x size RGB buffer."""
    scale = VIEWPORT / size
    # A pixel is one unit of the sampling grid; a distance of half a pixel either side of
    # an edge is the antialiasing ramp. Working in viewport units, that half-pixel is
    # scale/2, so coverage goes 1 -> 0 across the edge without any supersampling.
    edge = scale / 2.0

    rows = bytearray()
    for py in range(size):
        y = (py + 0.5) * scale
        row = bytearray()
        for px in range(size):
            x = (px + 0.5) * scale
            r, g, b = BACKGROUND
            for sdf, (sr, sg, sb) in SHAPES:
                d = sdf(x, y)
                if d >= edge:
                    continue
                cov = 1.0 if d <= -edge else (edge - d) / (2 * edge)
                r += (sr - r) * cov  # type: ignore[assignment]
                g += (sg - g) * cov  # type: ignore[assignment]
                b += (sb - b) * cov  # type: ignore[assignment]
            row += bytes((round(r), round(g), round(b)))
        rows += b"\x00" + row     # filter byte 0 (None) per scanline
    return rows


def write_png(path: pathlib.Path, size: int, rows: bytearray) -> None:
    def chunk(tag: bytes, data: bytes) -> bytes:
        return (
            struct.pack(">I", len(data))
            + tag
            + data
            + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)
        )

    # 8-bit RGB, no interlace. Play accepts 32-bit PNG; RGB without alpha is fine and
    # is what you want for a store icon (it must not be transparent).
    header = struct.pack(">IIBBBBB", size, size, 8, 2, 0, 0, 0)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", header)
        + chunk(b"IDAT", zlib.compress(bytes(rows), 9))
        + chunk(b"IEND", b"")
    )


def main() -> None:
    repo = pathlib.Path(__file__).resolve().parent.parent
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("-o", "--out", type=pathlib.Path,
                    default=repo / "docs" / "play" / "icon-512.png")
    ap.add_argument("-s", "--size", type=int, default=512)
    args = ap.parse_args()

    write_png(args.out, args.size, render(args.size))
    print(f"wrote {args.out} ({args.size}x{args.size})")


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Generate <CONSOLE>-<color>.svg copies of the frames in art/cartridges.

A port of the desktop app's tools/gen_cartridge_colors.py (Pillow instead of
GdkPixbuf, same palette and constants), for the frames only this app has.
Each copy adds a "Colorize" filter, with a luminance ramp measured from the
embedded art so the same color reads the same on every console.

    tools/gen-cartridge-colors.py [CONSOLE...]
"""
import base64
import io
import re
import sys
from pathlib import Path

from PIL import Image

ART = Path(__file__).resolve().parent.parent / "art" / "cartridges"

PALETTE = [
    ("black", "Onyx", "#26262A"),
    ("white", "Pearl", "#E8E4DA"),
    ("red", "Crimson", "#B23A34"),
    ("orange", "Amber", "#CC7A29"),
    ("yellow", "Sunflower", "#D9A81F"),
    ("green", "Emerald", "#3F8C5B"),
    ("teal", "Teal", "#2C8A8A"),
    ("blue", "Cobalt", "#3167B0"),
    ("purple", "Amethyst", "#7A4FA3"),
    ("pink", "Rose", "#BE5289"),
    ("gold", "Gold", "#C6A02E"),
    ("clear", "Clear", "#B9C0C7"),
]

TARGET_MID = 0.85
TARGET_SPREAD = 0.30
SLOPE_LIMITS = (0.80, 3.00)

DEFS_RE = re.compile(r'<defs\s+id="defs1"\s*/>')
IMAGE_OPEN_RE = re.compile(r"<image\b")


def _payload(text):
    m = re.search(r'xlink:href="data:image/png;base64,(.*?)"', text, re.S)
    return base64.b64decode(re.sub(r"(&#10;|\s)", "", m.group(1)))


def _percentile(hist, q):
    total = sum(hist)
    target, seen = total * q, 0
    for value, count in enumerate(hist):
        seen += count
        if seen >= target:
            return value / 255.0
    return 1.0


def measure_ramp(svg_text):
    img = Image.open(io.BytesIO(_payload(svg_text))).convert("RGBA")
    hist = [0] * 256
    for r, g, b, a in img.getdata():
        if a < 200:
            continue
        hist[(r * 299 + g * 587 + b * 114) // 1000] += 1
    p5, p50, p95 = (_percentile(hist, q) for q in (0.05, 0.5, 0.95))
    slope = min(max(TARGET_SPREAD / max(p95 - p5, 1e-4), SLOPE_LIMITS[0]), SLOPE_LIMITS[1])
    return slope, TARGET_MID - slope * p50


def filter_block(color_id, color_name, base_hex, ramp):
    slope, intercept = ramp
    func = f'\n           type="linear"\n           slope="{slope:.4f}"\n           intercept="{intercept:.4f}"'
    return (
        '<defs\n     id="defs1"><filter\n'
        f'       inkscape:label="Colorize {color_name}"\n'
        '       style="color-interpolation-filters:sRGB"\n'
        f'       id="colorize-{color_id}"><feColorMatrix\n'
        '         type="saturate"\n         values="0"\n         result="desaturated" /><feComponentTransfer\n'
        '         in="desaturated"\n         result="plastic"><feFuncR'
        f'{func} /><feFuncG{func} /><feFuncB{func} /></feComponentTransfer><feFlood\n'
        f'         flood-opacity="1"\n         flood-color="{base_hex}"\n         result="flood" /><feBlend\n'
        '         in="flood"\n         in2="plastic"\n         mode="multiply"\n         result="blend" /><feComposite\n'
        '         in="blend"\n         in2="desaturated"\n         operator="in"\n         result="composite1" /></filter></defs>'
    )


def main():
    consoles = sys.argv[1:] or sorted(p.stem for p in ART.glob("*.svg") if "-" not in p.stem)
    for console in consoles:
        text = (ART / f"{console}.svg").read_text()
        ramp = measure_ramp(text)
        print(f"{console:8} ramp: slope={ramp[0]:.3f} intercept={ramp[1]:+.3f}")
        for color_id, color_name, base_hex in PALETTE:
            out = DEFS_RE.sub(lambda _m: filter_block(color_id, color_name, base_hex, ramp), text, count=1)
            out = IMAGE_OPEN_RE.sub(lambda m: m.group(0) + f'\n       style="filter:url(#colorize-{color_id})"', out, count=1)
            out = out.replace(f'sodipodi:docname="{console}.svg"', f'sodipodi:docname="{console}-{color_id}.svg"')
            (ART / f"{console}-{color_id}.svg").write_text(out)


if __name__ == "__main__":
    main()

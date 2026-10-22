#!/usr/bin/env python3
"""Bake the desktop app's cartridge frames into PNGs for res/drawable-nodpi.

Each SVG embeds one PNG; the color variants add an Inkscape "colorize"
filter (desaturate, linear transfer, multiply by a flood color, keep alpha),
which Android cannot render, so it is applied here.

    tools/bake-frames.py ~/projetos/pessoal/OpenEmux/src/openemux/ui/assets/images/cartridges
"""
import base64
import io
import pathlib
import re
import sys

from PIL import Image

OUT = pathlib.Path(__file__).resolve().parent.parent / "src/main/res/drawable-nodpi"


def embedded_png(svg):
    href = re.search(r'<image[^>]*?(?:xlink:)?href="data:image/png;base64,([^"]+)"', svg, re.S).group(1)
    return Image.open(io.BytesIO(base64.b64decode(href.replace("&#10;", "")))).convert("RGBA")


def colorize(img, svg):
    funcs = re.findall(r'<feFunc[RGB][^>]*?slope="([-\d.]+)"[^>]*?intercept="([-\d.]+)"', svg, re.S)
    flood = re.search(r'flood-color="#([0-9A-Fa-f]{6})"', svg).group(1)
    fr, fg, fb = (int(flood[i:i + 2], 16) / 255 for i in (0, 2, 4))
    (sr, ir), (sg, ig), (sb, ib) = ((float(s), float(i)) for s, i in funcs)
    clamp = lambda v: min(1.0, max(0.0, v))
    px = img.load()
    out = Image.new("RGBA", img.size)
    po = out.load()
    for y in range(img.height):
        for x in range(img.width):
            r, g, b, a = px[x, y]
            if a == 0:
                continue
            lum = (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255
            p = (clamp(sr * lum + ir), clamp(sg * lum + ig), clamp(sb * lum + ib))
            k = a / 255
            # feBlend multiply over an opaque flood, then "in" the source alpha.
            c = [f * (1 - k + k * pc) for f, pc in zip((fr, fg, fb), p)]
            po[x, y] = (round(c[0] * 255), round(c[1] * 255), round(c[2] * 255), a)
    return out


def main(src):
    for path in sorted(pathlib.Path(src).expanduser().glob("*.svg")):
        console, _, color = path.stem.partition("-")
        svg = path.read_text()
        img = embedded_png(svg)
        if "feFlood" in svg:
            img = colorize(img, svg)
        name = f"cart_{console.lower()}" + (f"_{color}" if color else "") + ".png"
        img.save(OUT / name, optimize=True)
        print(name, img.size)


if __name__ == "__main__":
    main(sys.argv[1])

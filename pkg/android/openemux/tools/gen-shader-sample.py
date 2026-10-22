#!/usr/bin/env python3
"""Draw the shader picker's sample scene: original 256x224 pixel art.

No game's art or characters, only what tells the shaders apart: a stepped
gradient sky, diagonals, hard-edged tiles, small text and saturated colors.

    tools/gen-shader-sample.py
"""
from pathlib import Path

from PIL import Image, ImageDraw

OUT = Path(__file__).resolve().parent.parent / "src/main/res/drawable-nodpi/shader_sample.png"
W, H = 256, 224

# 3x5 glyphs, rows top to bottom, bits left to right.
FONT = {
    "0": ["111", "101", "101", "101", "111"], "1": ["010", "110", "010", "010", "111"],
    "2": ["111", "001", "111", "100", "111"], "3": ["111", "001", "111", "001", "111"],
    "4": ["101", "101", "111", "001", "001"], "5": ["111", "100", "111", "001", "111"],
    "6": ["111", "100", "111", "101", "111"], "7": ["111", "001", "010", "010", "010"],
    "8": ["111", "101", "111", "101", "111"], "9": ["111", "101", "111", "001", "111"],
    "S": ["111", "100", "111", "001", "111"], "C": ["111", "100", "100", "100", "111"],
    "O": ["111", "101", "101", "101", "111"], "R": ["110", "101", "110", "101", "101"],
    "E": ["111", "100", "110", "100", "111"], "L": ["100", "100", "100", "100", "111"],
    "V": ["101", "101", "101", "101", "010"], "T": ["111", "010", "010", "010", "010"],
    "I": ["111", "010", "010", "010", "111"], "M": ["101", "111", "111", "101", "101"],
    "x": ["000", "101", "010", "101", "000"], " ": ["000"] * 5,
}


def text(d, x, y, s, color):
    for ch in s:
        for row, bits in enumerate(FONT[ch]):
            for col, bit in enumerate(bits):
                if bit == "1":
                    d.point((x + col, y + row), fill=color)
        x += 4


def main():
    img = Image.new("RGB", (W, H))
    d = ImageDraw.Draw(img)

    # Sky: a stepped gradient, the bands a CRT smooths and Sharp keeps.
    top, bottom = (24, 32, 120), (250, 120, 60)
    for i in range(17):
        t = i / 16
        c = tuple(round(a + (b - a) * t) for a, b in zip(top, bottom))
        d.rectangle([0, i * 9, W, i * 9 + 8], fill=c)

    # Sun, with hard bands across it.
    d.ellipse([170, 40, 226, 96], fill=(255, 220, 60))
    for y in (70, 78, 86):
        d.rectangle([168, y, 228, y + 2], fill=(250, 140, 70))

    # Mountains: diagonals at two slopes.
    d.polygon([(0, 150), (60, 90), (120, 150)], fill=(110, 40, 150))
    d.polygon([(80, 150), (150, 80), (220, 150)], fill=(150, 50, 170))
    d.polygon([(180, 150), (230, 110), (256, 136), (256, 150)], fill=(110, 40, 150))
    d.polygon([(150, 80), (162, 92), (150, 88), (138, 92)], fill=(240, 240, 255))

    # Ground: hard-edged tiles.
    for x in range(0, W, 16):
        for y in range(160, H, 16):
            d.rectangle([x, y, x + 15, y + 15], fill=(200, 90, 30) if (x // 16 + y // 16) % 2 else (230, 120, 40))
            d.line([(x, y), (x + 15, y)], fill=(255, 190, 90))
    d.rectangle([0, 150, W, 159], fill=(40, 200, 80))
    for x in range(0, W, 4):
        d.point((x, 149), fill=(40, 200, 80))

    # A floating platform, a staircase diagonal and a block of question tiles.
    d.rectangle([24, 112, 87, 119], fill=(0, 170, 220), outline=(255, 255, 255))
    for i in range(6):
        d.rectangle([150 + i * 8, 142 - i * 8, 157 + i * 8, 149], fill=(60, 60, 70), outline=(20, 20, 24))
    for i in range(3):
        x = 104 + i * 18
        d.rectangle([x, 104, x + 15, 119], fill=(255, 200, 0), outline=(120, 60, 0))
        d.rectangle([x + 6, 108, x + 9, 113], fill=(120, 60, 0))
        d.point((x + 7, 116), fill=(120, 60, 0))

    # Coins: small round edges.
    for x in (36, 52, 68):
        d.ellipse([x, 96, x + 7, 105], fill=(255, 230, 0), outline=(180, 120, 0))

    # HUD: small text, the first thing a blurry filter ruins.
    d.rectangle([0, 0, W, 12], fill=(0, 0, 0))
    text(d, 4, 4, "SCORE 012340", (255, 255, 255))
    text(d, 112, 4, "Mx3", (255, 80, 80))
    text(d, 176, 4, "TIME 299", (120, 255, 140))

    img.save(OUT, optimize=True)
    print(OUT)


if __name__ == "__main__":
    main()

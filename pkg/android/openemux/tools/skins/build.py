"""Builds the bundled controller skins (src/main/assets/skins) from the design.

  python3 tools/skins/build.py [CONSOLE ...]

The layout and the art have one source: the engine of the design canvas's
Skin component (Skin.dc.html, between ENGINE START and ENGINE END). Each
representation (phone and tablet, portrait and landscape) is rendered by
headless Chrome from render.html, the game's screens are cut out
(transparent), and info.json gets the frames the engine laid out. Needs
Node, Google Chrome and ImageMagick.
"""
import json
import shutil
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
APP = HERE.parent.parent
ASSETS = APP / "src/main/assets/skins"
WORK = APP / "build/skins"
CHROME = shutil.which("google-chrome") or shutil.which("google-chrome-stable")

# Delta's identifiers where Delta has the console, Manic EMU's otherwise (and then a .manicskin).
TYPES = {
    "FC": "com.rileytestut.delta.game.nes", "SFC": "com.rileytestut.delta.game.snes",
    "N64": "com.rileytestut.delta.game.n64", "GBC": "com.rileytestut.delta.game.gbc",
    "GBA": "com.rileytestut.delta.game.gba", "NDS": "com.rileytestut.delta.game.ds",
    "MD": "com.rileytestut.delta.game.genesis",
    "GB": "public.aoshuang.game.gb", "GC": "public.aoshuang.game.ngc", "SMS": "public.aoshuang.game.ms",
    "PS": "public.aoshuang.game.ps1", "PSP": "public.aoshuang.game.psp",
}
# The format's families: iphone/edgeToEdge for phones, ipad/standard for tablets; px per unit.
REPS = [("phone", "iphone", "edgeToEdge", 3), ("tablet", "ipad", "standard", 2)]
# The D-pad's circle: one image per console, stretched by the app over each D-pad's travel square.
KNOB_UNITS, KNOB_SCALE = 100, 4


def sync_engine():
    src = (HERE / "Skin.dc.html").read_text()
    engine = src.split("// ENGINE START")[1].split("// ENGINE END")[0]
    (HERE / "engine.js").write_text(engine + "\nwindow.OE = OE;\n")


def engine_layout(console, device, orient):
    script = (
        "global.window={};require(process.argv[1]);"
        "const L=window.OE.layout(process.argv[2],process.argv[3],process.argv[4],{export:true});"
        "console.log(JSON.stringify({W:L.W,H:L.H,items:L.items,screens:L.screens,knobs:L.knobs.map(k=>({name:k.name,size:k.size})),name:L.name}))"
    )
    out = subprocess.run(["node", "-e", script, str(HERE / "engine.js"), console, device, orient],
                         capture_output=True, text=True, check=True).stdout
    return json.loads(out)


def screenshot(console, device, orient, w, h, scale, out, profile, query=""):
    url = f"file://{HERE / 'render.html'}?console={console}&device={device}&orient={orient}{query}"
    subprocess.run([
        CHROME, "--headless=new", "--disable-gpu", "--hide-scrollbars", f"--user-data-dir={profile}",
        f"--force-device-scale-factor={scale}", f"--window-size={w},{h}", "--default-background-color=00000000",
        "--virtual-time-budget=4000", f"--screenshot={out}", url,
    ], check=True, capture_output=True)


def cut_screens(png, screens, scale):
    args = ["convert", str(png), "-alpha", "set"]
    for s in screens:
        x, y, w, h = (round(s[k] * scale) for k in ("x", "y", "width", "height"))
        args += ["-region", f"{w}x{h}+{x}+{y}", "-alpha", "transparent", "+region"]
    subprocess.run(args + [str(png)], check=True)


def build(console, profile):
    folder = WORK / console
    folder.mkdir(parents=True, exist_ok=True)
    reps = {}
    files = []
    name = None
    knob = None
    for device, family, variant, scale in REPS:
        for orient in ("portrait", "landscape"):
            L = engine_layout(console, device, orient)
            name = L["name"]
            image = f"{device}_{orient}.png"
            screenshot(console, device, orient, L["W"], L["H"], scale, folder / image, profile)
            cut_screens(folder / image, L["screens"], scale)
            # Each stick's knob, an image of its own the app moves with the thumb.
            knobs = {}
            for i, k in enumerate(L["knobs"]):
                file = f"{device}_{orient}_{k['name']}"
                px = round(k["size"] * scale)
                screenshot(console, device, orient, px // scale + 1, px // scale + 1, scale, folder / file, profile, f"&knob={i}")
                subprocess.run(["convert", str(folder / file), "-crop", f"{px}x{px}+0+0", "+repage", str(folder / file)], check=True)
                knobs[k["name"]] = file
            files.extend(knobs.values())
            items = []
            for it in L["items"]:
                entry = {"inputs": it["inputs"], "frame": it["frame"]}
                if it.get("extendedEdges"):
                    entry["extendedEdges"] = it["extendedEdges"]
                if it.get("thumbstick"):
                    entry["thumbstick"] = dict(it["thumbstick"], name=knobs[it["thumbstick"]["name"]])
                if it.get("knob"):
                    # Not in Delta's format: the app's own key for the D-pad's moving circle.
                    entry["knob"] = it["knob"]
                    knob = it["knob"]
                items.append(entry)
            reps.setdefault(family, {}).setdefault(variant, {})[orient] = {
                "assets": {"large": image},
                "mappingSize": {"width": L["W"], "height": L["H"]},
                "extendedEdges": {"top": 6, "bottom": 6, "left": 6, "right": 6},
                "translucent": False,
                "screens": [{"outputFrame": s} for s in L["screens"]],
                "items": items,
            }
    info = {
        "name": f"OpenEmux {name}",
        "identifier": f"openemux.skin.{console.lower()}",
        "gameTypeIdentifier": TYPES[console],
        "debug": False,
        "representations": reps,
    }
    (folder / "info.json").write_text(json.dumps(info, indent=2, ensure_ascii=False))
    if knob:
        screenshot(console, "phone", "portrait", KNOB_UNITS, KNOB_UNITS, KNOB_SCALE, folder / knob, profile,
                   f"&dpadKnob={KNOB_UNITS}")
    ext = ".manicskin" if TYPES[console].startswith("public.aoshuang") else ".deltaskin"
    pkg = ASSETS / f"{console}{ext}"
    with zipfile.ZipFile(pkg, "w", zipfile.ZIP_DEFLATED) as z:
        z.write(folder / "info.json", "info.json")
        for device, *_ in REPS:
            for orient in ("portrait", "landscape"):
                z.write(folder / f"{device}_{orient}.png", f"{device}_{orient}.png")
        for file in files:
            z.write(folder / file, file)
        if knob:
            z.write(folder / knob, knob)
    print(f"{pkg.relative_to(APP)}  {pkg.stat().st_size // 1024} KB")


def main():
    consoles = sys.argv[1:] or list(TYPES)
    unknown = [c for c in consoles if c not in TYPES]
    if unknown:
        raise SystemExit(f"unknown console: {', '.join(unknown)} (one of {', '.join(TYPES)})")
    sync_engine()
    ASSETS.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as profile:
        for c in consoles:
            build(c, profile)


main()

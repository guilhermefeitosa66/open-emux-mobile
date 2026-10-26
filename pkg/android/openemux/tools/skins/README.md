# Bundled controller skins

`build.py` builds `src/main/assets/skins/<console>.deltaskin` (or
`.manicskin`), the skins of the twelve best-known consoles, from one source:
`Skin.dc.html`, the Skin component of the skins' design canvas. Its script
holds the layout engine (between `ENGINE START` and `ENGINE END`) and each
console's colors, measured on photos of the console's best-known shell
(Wikimedia Commons).

```sh
python3 tools/skins/build.py          # all twelve
python3 tools/skins/build.py GBA PS   # some
```

For each console it lays out four representations (phone and tablet,
portrait and landscape: `iphone/edgeToEdge` and `ipad/standard` in the
format), renders each with headless Chrome from `render.html` (3 px per unit
on phones, 2 on tablets), cuts the game's screens out of the art and writes
`info.json` with the frames the engine laid out. A stick's knob is not in
the art: each one is rendered on its own, the size of its travel, and the
stick item names it as its `thumbstick` (`<device>_<orient>_knob<N>.png`), so
the app can move it with the thumb. Nor is the D-pad's circle, which the app
moves with the thumb too: it is one more image per package (`dpad_knob.png`,
the circle on its travel square), which each D-pad item names under `knob`, a
key of the app's own that other emulators ignore. Intermediate images go to
`build/skins/`. Needs Node, Google Chrome and ImageMagick.

To change a skin, change `Skin.dc.html` (on the canvas, or here) and run the
build. `launch/ImportedSkinRenderer.kt` reads the packages.

`SpaceGrotesk.ttf` is the labels' typeface, under the SIL Open Font License
(`SpaceGrotesk-OFL.txt`); only the rendered art ships in the app.

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
`info.json` with the frames the engine laid out. Intermediate images go to
`build/skins/`. Needs Node, Google Chrome and ImageMagick.

To change a skin, change `Skin.dc.html` (on the canvas, or here) and run the
build. `launch/ImportedSkinRenderer.kt` reads the packages.

`SpaceGrotesk.ttf` is the labels' typeface, under the SIL Open Font License
(`SpaceGrotesk-OFL.txt`); only the rendered art ships in the app.

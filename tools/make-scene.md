# Workflow: Make a Horizon wallpaper from your own photo

**When to use this:** you want a photo of yours (or any picture you have the right to use) as a scene in the
`horizon` theme, or in a theme of your own, with the big glass clock set into it. It runs on your computer,
offline, with no AI: `tools/make-scene.py` makes the pictures and writes the clock's place into the theme's
`config.json`. Nothing is sent anywhere, and nothing from the photo's metadata (EXIF, camera, GPS) is kept.

What a scene is, in the theme's `wallpapers/` folder (the keys are in `themes/README.md`, `scene_clock`,
`wallpaper_depth` and `scene_clock_layouts`):

| File | What it is |
|---|---|
| `<name>.jpg` | The wallpaper, 1920x1080, under 300 KB. |
| `<name>_glass.jpg` | A blurred, brightened copy. The clock's digits are filled with it, which is what makes them look like frosted glass. |
| `<name>_fg.webp` | Optional: the scenery with the sky transparent, drawn over the clock so hills and peaks overlap the bottom of the digits. |

Plus one entry in the theme's `config.json`, `scene_clock_layouts["<name>"]`.

## 1. Install the picture libraries (once)

They are not part of the tools' normal install, and the tests do not need them:

```sh
python3 -m venv tools/venv
tools/venv/bin/pip install -r tools/requirements-scenes.txt
```

On Windows the same, with `tools\venv\Scripts\pip.exe`. numpy and Pillow are needed for every run; scipy only for
`--depth`; pymatting (an exact edge for `--depth`) and pillow-heif (iPhone HEIC photos) are optional.

## 2. Pick a photo

- **Landscape, ideally 16:9, at least 1920 px wide.** A taller photo is cut to a 16:9 band: `--top 0` takes the
  very top, `--top 1` the bottom (default `0.1`). A wider one is cut from the middle.
- **A calm area where the clock goes**, usually the top half: sky, water, a wall, soft background. Faces and busy
  detail behind the digits make the time hard to read.
- **Open sky above a clear edge** (a ridge, a skyline, a line of trees) is what the optional cut-out needs.
- **Dark or mid-tone scenes** look best behind white glass. A pale picture works with `--tint dark`.
- Use art you have the right to use: `themes/horizon/CREDITS.md` shows how the shipped scenes are credited.

## 3. Make it

```sh
tools/venv/bin/python tools/make-scene.py <photo> 06-valley
```

On Windows: `tools\venv\Scripts\python.exe tools\make-scene.py <photo> 06-valley`.

`<photo>` is your picture (JPEG, PNG, WebP or HEIC) and `06-valley` is the wallpaper's name:
lowercase letters, digits and `-`. The theme shows its wallpapers in name
order, so a number in front picks the place (Horizon's five are `01` to `05`). The tool writes
`themes/horizon/wallpapers/<name>.jpg` and `<name>_glass.jpg`, then merges a suggested clock layout into
`themes/horizon/config.json` and prints it. Every other key in the file, and its order, stays as it was.

For a theme of your own, anywhere on the computer, name its folder:

```sh
tools/venv/bin/python tools/make-scene.py <photo> 01-beach --theme D:\Personal_Projects\CleanMyTV\my-theme
```

A folder with no `config.json` gets one with the keys a scene theme needs. `--no-config` leaves `config.json`
alone and only prints the layout.

A very detailed photo is saved a little smaller than 1920x1080 (still 16:9, and the launcher scales it) when that is
the only way under the 300 KB rule that `tests/test_theme_wallpapers.py` holds every wallpaper to.

## 4. The clock

The layout the tool writes is a suggestion: with a cut-out it puts the clock on the side with the most open sky,
and high enough that the skyline crosses only the lowest third of the digits; without one it is centred. A pale
picture behind the digits gets `"tint": "dark"`. Change a value in `config.json`, or run the tool again with
`--clock-x`, `--clock-y` and the others below: the entry for that name is replaced where it stands.

| Field | Command-line flag | Range | What it does |
|---|---|---|---|
| `x` | `--clock-x` | `left`, `centre`, `right` | Where the clock and the date line above it sit. |
| `y` | `--clock-y` | 0 to 0.8 | Top of the digits, as a share of the screen height. Smaller moves the clock up. |
| `size` | `--clock-size` | 0.10 to 0.70 | Font size, as a share of the screen height. 0.37 is the Horizon size. |
| `inset` | `--clock-inset` | 0 to 0.40 | Distance of a `left` or `right` clock from the edge, as a share of the width (0.05). |
| `tint` | `--tint` | `light`, `dark` | `dark` is a smoky clock with no sheen and dark date text, for a pale scene. |
| `date_y` | `--date-y` | 0 to 0.9 | Top of the date line (default 0.05 above the clock). |
| `rim`, `date_color` | `--rim`, `--date-color` | `#RRGGBB`, `#AARRGGBB` | Optional colours of the glass rim and the date line. |

A value outside its range is refused, because the launcher would quietly take its default instead. Keep at least the
top 60% of every digit clear of the scenery so the time reads at a glance: too much hidden, make `y` or `size`
smaller. The clock goes behind everything in the cut-out, including a tree or a cliff at the side.

## 5. Optional: the skyline in front of the clock (`--depth`)

For a picture with open sky above a clear edge, add `--depth` (the no-AI route by roliusz, from their fork of
couch-tv; it needs scipy, and pymatting for the exact edge) and `--preview`:

```sh
tools/venv/bin/python tools/make-scene.py <photo> 06-valley --depth --preview
```

It writes `<name>_fg.webp` and, with `--preview`, `build/scenes/<name>-check.jpg`: the cut-out over flat
magenta. **Open it and look.** Magenta should be exactly where the sky was, and only there. The theme needs
`"wallpaper_depth": true`, and the tool says so when it is missing. The sky is grown down from the top edge, so it
works best on a clear or smoothly graded sky; small patches of sky left in the corners, away from the clock, do
not matter. If no clear sky is found the cut-out is skipped, and the clock sits over the whole photo, which is fine.

| What you see in the check image | Try |
|---|---|
| Patches of sky left over the top (not magenta) | `--far 70`: the sky may drift further from the top's colour |
| Magenta runs down into the hills, a lake or the trees | `--far 30`, or `--floor 0.5` (nothing below half way is sky) |
| A cloudy, colourful or sunset sky comes out in pieces | This route cannot tell clouds from scenery: leave `--depth` out |
| Fog or mist between the sky and the trees | `--smooth 4`, or `--floor 0.45 --treeline 3.5` |
| Narrow slivers of magenta cutting into a snowy face | `--fill 40` |
| Power lines or poles stay in front of the clock | `--thin 6` |
| A soft halo along the skyline | Install pymatting for the exact edge (on once it is there) |

The other options, `--step`, `--paint`, `--spread` and `--band`, are in the top of `tools/make-scene.py`.

## 6. Optional: a cut-out from an image model's magenta edit (`--sky-edit`)

**Not needed, and not part of the tool's normal use.** If the no-AI cut-out is not good enough, you can make the
cut-out with an image-editing model of your own choosing, outside this tool: give it the photo and this prompt, then
save the result.

> Edit this photograph: replace the entire sky with one flat, solid, pure magenta colour (#FF00FF), with no gradient,
> no clouds and no glow. Every other pixel stays exactly the same: the buildings, the trees and every leaf and
> branch, the hills, mountains and their edges, the water and reflections. Do not move, resize, crop or redraw
> anything. Only the sky changes.

```sh
tools/venv/bin/python tools/make-scene.py <photo> 06-valley --sky-edit <edit> --preview
```

The tool runs offline: it only reads your edit. It checks the edit against the photo first, prints how well they
line up (`mismatch outside the sky 3.8 (under 14 is fine)`), and skips the cut-out when the model moved or redrew the
scene, since that shows a ghost edge over the digits; make the edit again. `--open-sky` and `--extend 20` carry the
sky down through clouds or haze the model left unpainted, and `--limit 18` allows a hazy photo that still lines up.

## 7. Look at it, then push it

Check `themes/horizon/wallpapers/` has the new files, then push the theme to the TV and look at Home:

```sh
tools\theme.ps1 -TvHost <tv ip> themes\horizon
```

(`TV_HOST=<tv ip> tools/theme.sh themes/horizon` on macOS and Linux.) The scenes take turns in name order. To drop a
wallpaper, delete its three files and its entry in `scene_clock_layouts`.

If the scene is for this repository's `themes/horizon`, also add its line to `themes/horizon/CREDITS.md`, and run the
tests: `python3 -m unittest discover -s tests -v`.

## Troubleshooting

| What you see | Do this |
|---|---|
| `make-scene.py needs numpy and Pillow` | Step 1: `pip install -r tools/requirements-scenes.txt` into the Python you run it with. |
| `needs scipy` with `--depth` | The same file; scipy is only for `--depth`. |
| The name is refused | Lowercase letters, digits and `-` only, up to 40. No `_` (a name ending `_fg` or `_glass` would be read as a companion file and never shown), no spaces, not `default`. |
| `cannot read ... as a picture` | A HEIC photo needs pillow-heif (in the requirements file); or export it as JPEG. |
| The clock did not move after a push | The layout's key is the wallpaper's name without its extension. Check the spelling in `scene_clock_layouts`, and that the theme has `"scene_clock": true`. |
| The glass clock is faint over a bright area | `--tint dark`, or move it with `--clock-y` and `--clock-x`. |
| No cut-out on the TV | The theme needs `"wallpaper_depth": true`, and `<name>_fg.webp` has to exist. |
| Digits partly hidden by scenery | Make `--clock-y` smaller (it moves up) or `--clock-size` smaller. |
| `config.json is not valid JSON` | Nothing was changed. Fix the file, or use `--no-config` and paste the printed layout in yourself. |
| `no clear sky found at the top` | The cut-out is skipped; try `--far 70`, or leave `--depth` out. |

## Credits

The glass fill and the layout idea come from couch-tv's `tools/make-scene.py` (MIT, Seagey,
https://github.com/Seagey/couch-tv); the no-AI route (growing the sky down from the top edge, its options and the
exact edge with alpha matting) is roliusz's, from their fork. The tool keeps their licence header.

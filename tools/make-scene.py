#!/usr/bin/env python3
"""Make a "Horizon" wallpaper from any photo, on this computer: the photo at 1920x1080, the frosted copy
the launcher's glass clock is filled with, optionally the cut-out of the scenery that is drawn over the
clock, and the clock's place in the theme's config.json. docs/workflows/make-a-scene.md walks through it.

    python3 tools/make-scene.py PHOTO NAME                          the wallpaper and its glass copy
    python3 tools/make-scene.py PHOTO NAME --depth --preview        plus the cut-out, found without AI
    python3 tools/make-scene.py PHOTO NAME --sky-edit EDIT --preview   plus the cut-out, from an image model's edit
    python3 tools/make-scene.py PHOTO NAME --theme D:\\my-theme      into another theme folder

  PHOTO   any photo Pillow reads (JPEG, PNG, WebP; HEIC with pillow-heif). It is cut to a 16:9 band
          (--top says where) and made 1920x1080.
  NAME    the wallpaper's name: lowercase letters, digits and '-', e.g. "06-valley". The theme shows its
          wallpapers in name order, so a number in front picks the place.

Writes, in THEME/wallpapers/ (THEME is --theme, themes/horizon by default):
    NAME.jpg          the wallpaper, 1920x1080, under 300 KB (the size rule of tests/test_theme_wallpapers.py)
    NAME_glass.jpg    a blurred, brightened copy that fills the clock's digits like frosted glass
    NAME_fg.webp      only with --depth or --sky-edit: the scenery with the sky transparent, drawn over the
                      clock by wallpaper_depth so peaks and hills overlap the bottom of the digits
and merges a suggested clock layout into THEME/config.json, scene_clock_layouts[NAME] (x, y, size, tint,
date_y, and inset on a side), leaving every other key where it was. --no-config skips that.

Extra depth, optional. Two ways to find the sky:

  --depth (no AI): the sky is grown down from the top edge: a pixel is sky when it is close to the sky just
    above it (and beside it), so a smooth gradient (a sunset) stays sky while a skyline, which changes
    sharply, stops it. Needs scipy; with pymatting the edge is exact. Its options are --step, --far,
    --floor, --paint, --smooth, --treeline, --thin, --spread, --fill and --band.
  --sky-edit EDIT (optional, made outside this tool): EDIT is the same photo with the sky painted flat
    magenta by an image model (the prompt is in the guide). Every magenta pixel is sky. The edit is checked
    against the photo first, and if the two do not line up the cut-out is skipped.

--preview [DIR] also writes DIR/NAME-check.jpg (default build/scenes/): the cut-out over magenta, so a bad
edge is easy to see before it reaches the TV. Nothing leaves this computer, nothing is downloaded, and nothing
from the photo's metadata (EXIF, camera, location, colour profile) is copied into the files.

Needs numpy and Pillow; scipy for --depth. Optional: pymatting (exact edges with --depth) and pillow-heif
(iPhone HEIC photos). They are not in tools/requirements.txt, so the tests need none of them:
    tools/venv/bin/pip install -r tools/requirements-scenes.txt

Adapted from couch-tv's tv-home/tools/make-scene.py, MIT licence, (c) Seagey,
https://github.com/Seagey/couch-tv. The no-AI route (growing the sky down from the top edge, its options and
the exact edge with alpha matting) is roliusz's, from their fork of couch-tv, https://github.com/roliusz.
The licence is the repository's own (MIT): see LICENSE.
"""
import argparse
import json
import os
import re
import sys

W, H = 1920, 1080
HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.normpath(os.path.join(HERE, ".."))
DEFAULT_THEME = os.path.join(ROOT, "themes", "horizon")
DEFAULT_PREVIEW = os.path.join(ROOT, "build", "scenes")
REQUIREMENTS = "tools/requirements-scenes.txt"
REQ_PATH = os.path.join(ROOT, "tools", "requirements-scenes.txt")

# The wallpaper rule of docs/workflows/theme.md: every file in wallpapers/ stays under about 300 KB.
LIMIT_BYTES = 300 * 1024 - 2048

# The ranges the launcher accepts for a clock layout (launcher/.../SceneClockLayout.java: anything outside
# them takes the default, so a value written here must sit inside them to do what it says).
Y_RANGE = (0.0, 0.8)           # top of the clock's line box, share of the screen height
SIZE_RANGE = (0.10, 0.70)      # font size, share of the screen height
INSET_RANGE = (0.0, 0.40)      # a left or right clock's distance from the edge, share of the width
DATE_Y_RANGE = (0.0, 0.9)      # top of the date line, share of the screen height
SIDES = ("left", "centre", "right")
TINTS = ("light", "dark")
DEFAULT_SIZE = 0.37
DEFAULT_INSET = 0.05

NAME_RE = re.compile(r"[a-z0-9][a-z0-9-]{0,39}")
RESERVED_NAMES = {"default", "wallpaper"}   # "default" is the layout every other wallpaper takes
COLOR_RE = re.compile(r"#[0-9A-Fa-f]{6}")
COLOR_ARGB_RE = re.compile(r"#[0-9A-Fa-f]{6}(?:[0-9A-Fa-f]{2})?")


# ---------------------------------------------------------------- the parts that need no image library

def check_name(name):
    """The name if the launcher and the push will take it as a wallpaper's, else a ValueError saying why.

    Lowercase letters, digits and '-', so no '_' (a name ending _fg or _glass would be read as a companion
    file and never shown), no dot, no space, and not the "default" layout's name."""
    if not isinstance(name, str) or not NAME_RE.fullmatch(name):
        raise ValueError(f'"{name}": use lowercase letters, digits and "-" only, starting with a letter or a '
                         f'digit, up to 40 (for example 06-valley)')
    if name in RESERVED_NAMES:
        raise ValueError(f'"{name}" is a name the theme keeps for itself: pick another')
    return name


def _in_range(label, value, bounds):
    if value is None:
        return None
    if isinstance(value, bool) or not isinstance(value, (int, float)) or value != value:
        raise ValueError(f"{label} must be a number, not {value!r}")
    lo, hi = bounds
    if value < lo or value > hi:
        raise ValueError(f"{label} {value:g} is outside {lo:g} to {hi:g}, the range the launcher accepts")
    return float(value)


def build_layout(x="centre", y=0.14, size=DEFAULT_SIZE, tint="light", date_y=None, inset=None,
                 rim=None, date_color=None):
    """One scene_clock_layouts entry, in the key order the shipped themes use, every value inside the
    launcher's range (a ValueError names the one that is not). date_y defaults to 0.05 above the clock, the
    launcher's own default; inset is written only for a left or right clock."""
    x = (x or "centre").strip().lower()
    if x not in SIDES:
        raise ValueError(f'x must be one of {", ".join(SIDES)}, not "{x}"')
    tint = (tint or "light").strip().lower()
    if tint not in TINTS:
        raise ValueError(f'tint must be light or dark, not "{tint}"')
    y = _in_range("y", y, Y_RANGE)
    size = _in_range("size", size, SIZE_RANGE)
    inset = _in_range("inset", inset, INSET_RANGE)
    if date_y is None:
        date_y = max(0.02, y - 0.05)
    date_y = _in_range("date_y", date_y, DATE_Y_RANGE)
    if rim is not None and not COLOR_RE.fullmatch(rim):
        raise ValueError(f'rim must be #RRGGBB, not "{rim}"')
    if date_color is not None and not COLOR_ARGB_RE.fullmatch(date_color):
        raise ValueError(f'date_color must be #RRGGBB or #AARRGGBB, not "{date_color}"')
    entry = {"x": x}
    if x != "centre":
        entry["inset"] = round(DEFAULT_INSET if inset is None else inset, 3)
    entry["y"] = round(y, 3)
    entry["size"] = round(size, 3)
    entry["tint"] = tint
    entry["date_y"] = round(date_y, 3)
    if rim is not None:
        entry["rim"] = rim.upper()
    if date_color is not None:
        entry["date_color"] = date_color.upper()
    return entry


def layout_from_lows(low, height=H, tint="light"):
    """The clock's place from where the scenery starts, in pixels from the top, per zone of the picture.

    `low` maps "left", "centre" and "right" to the median row where the scenery begins in the columns a clock
    about 700 px wide would cover there (the whole height when that is open sky). The clock goes on the side
    with the most open sky (a tie goes to the centre), high enough that the skyline there crosses only the
    lowest third of the digits (Antonio's ink runs from 0.05 to 0.92 of the font size below the clock's top).
    This is couch-tv's own rule, in this launcher's fields."""
    side = max(("centre", "left", "right"), key=lambda k: low[k] + (1 if k == "centre" else 0))
    y = round(min(max(low[side] / height - 0.66 * DEFAULT_SIZE, 0.12), 0.62 - 0.92 * DEFAULT_SIZE), 2)
    return build_layout(x=side, y=y, size=DEFAULT_SIZE, tint=tint)


def merge_layout(config_text, name, entry):
    """config.json's text with scene_clock_layouts[name] set to `entry`: the other keys and their order
    stay, a layout that is already there is replaced where it stands, a new one goes last, and a missing
    scene_clock_layouts object goes in after wallpaper_depth (or last). Written back the way the shipped
    themes are: two-space indent, a closing newline. A ValueError says why the text cannot be merged into."""
    try:
        data = json.loads(config_text)
    except ValueError as err:
        raise ValueError(f"config.json is not valid JSON ({err}); nothing was changed") from None
    if not isinstance(data, dict):
        raise ValueError("config.json is not a JSON object; nothing was changed")
    layouts = data.get("scene_clock_layouts")
    if layouts is None:
        layouts = {}
        merged = {}
        placed = False
        for key, value in data.items():
            if key == "scene_clock_layouts":
                continue
            merged[key] = value
            if key == "wallpaper_depth":
                merged["scene_clock_layouts"] = layouts
                placed = True
        if not placed:
            merged["scene_clock_layouts"] = layouts
        data = merged
    elif not isinstance(layouts, dict):
        raise ValueError("scene_clock_layouts in config.json is not an object; nothing was changed")
    layouts[name] = entry
    return json.dumps(data, indent=2, ensure_ascii=False) + "\n"


def config_notes(config_text, with_cutout):
    """Switches the theme needs for what was just made, as sentences; none when it has them all."""
    try:
        data = json.loads(config_text)
    except ValueError:
        return []
    notes = []
    if data.get("scene_clock") is not True:
        notes.append('this theme does not have "scene_clock": true, so the glass clock is not drawn')
    if with_cutout and data.get("wallpaper_depth") is not True:
        notes.append('this theme does not have "wallpaper_depth": true, so the cut-out is not drawn')
    return notes


NEW_CONFIG = {
    "columns": 6,
    "background_color": "#090C14",
    "wallpaper": True,
    "wallpaper_rotation_minutes": 10,
    "wallpaper_order": "sequential",
    "wallpaper_dim": 0,
    "wallpaper_true_color": True,
    "scene_clock": True,
    "wallpaper_depth": True,
    "scene_clock_layouts": {},
}


def write_config(path, name, entry, with_cutout):
    """Merge `entry` into the theme's config.json (made from NEW_CONFIG when there is none). Returns the
    notes to print. The file is replaced in one step, so a failure cannot leave half a config."""
    created = not os.path.isfile(path)
    if created:
        text = json.dumps(NEW_CONFIG, indent=2) + "\n"
    else:
        with open(path, encoding="utf-8-sig", newline="") as f:
            text = f.read()
    crlf = "\r\n" in text
    merged = merge_layout(text, name, entry)
    notes = config_notes(merged, with_cutout)
    if crlf:
        merged = merged.replace("\n", "\r\n")
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8", newline="") as f:
        f.write(merged)
    os.replace(tmp, path)
    return (["config.json did not exist: made one with the keys a scene theme needs"] if created else []) + notes


# ---------------------------------------------------------------- the image library

def load_imaging(depth):
    """Import numpy and Pillow (and scipy for --depth) into this module, or exit saying what to install."""
    global np, Image, ImageEnhance, ImageFilter, ImageOps
    missing = []
    try:
        import numpy as np
    except ImportError:
        missing.append("numpy")
    try:
        from PIL import Image, ImageEnhance, ImageFilter, ImageOps
    except ImportError:
        missing.append("Pillow")
    if depth:
        try:
            import scipy  # noqa: F401
        except ImportError:
            missing.append("scipy")
    if missing:
        sys.exit(f"make-scene.py needs {' and '.join(missing)}, which is not installed. Install them with:\n"
                 f"    {sys.executable} -m pip install -r {REQ_PATH}\n"
                 f"(pymatting, in the same file, is optional: it gives --depth its exact edge.)")
    try:
        import pillow_heif
        pillow_heif.register_heif_opener()
    except ImportError:
        pass


def band(img, top):
    """A 16:9 band of the photo, the right way up, 1920x1080."""
    img = ImageOps.exif_transpose(img).convert("RGB")
    w, h = img.size
    bh = round(w * 9 / 16)
    if bh <= h:   # taller than 16:9: a band of the full width
        y = round(min(max(top, 0.0), 1.0) * (h - bh))
        img = img.crop((0, y, w, y + bh))
    else:         # wider: the middle
        bw = round(h * 16 / 9)
        x = (w - bw) // 2
        img = img.crop((x, 0, x + bw, h))
    return img.resize((W, H), Image.LANCZOS)


def clean(img):
    """The pixels only: a fresh image, so no EXIF, GPS, ICC or comment can ride along."""
    return Image.fromarray(np.array(img))


def save_under(img, path, **options):
    """Save `img` to `path`, lowering the quality a step at a time until the file is under the wallpaper
    size rule. Returns the quality used; warns when even the lowest is too big."""
    quality = options.pop("quality")
    for q in range(quality, 63, -4):
        img.save(path, quality=q, **options)
        if os.path.getsize(path) <= LIMIT_BYTES:
            return q
    # a very detailed photo: keep the quality and make the picture smaller (still 16:9; the launcher
    # scales every wallpaper and its companions to the screen)
    for scale in (0.9, 0.8, 0.7, 0.6, 0.5):
        small = img.resize((round(W * scale), round(H * scale)), Image.LANCZOS)
        small.save(path, quality=72, **options)
        if os.path.getsize(path) <= LIMIT_BYTES:
            print(f"note: {os.path.basename(path)} is a detailed picture, so it is saved at "
                  f"{small.size[0]}x{small.size[1]} to stay under 300 KB")
            return 72
    print(f"warning: {os.path.basename(path)} is {os.path.getsize(path) // 1024} KB, over the 300 KB rule of "
          f"tests/test_theme_wallpapers.py")
    return 72


# ---------------------------------------------------------------- with an image model's edit (optional)

def magenta_sky(img, edit_path, top, photo_aspect, limit, open_sky, extend):
    """The sky from the magenta edit, or None when the edit does not line up with the photo."""
    raw = Image.open(edit_path)
    edit = ImageOps.exif_transpose(raw).convert("RGB")
    ew, eh = edit.size
    if abs(ew / eh - 16 / 9) < 0.02:
        edit = edit.resize((W, H), Image.LANCZOS)   # an edit of the 16:9 copy (or a 16:9 photo)
    elif abs(ew / eh - photo_aspect) < 0.02:
        edit = band(raw, top)                        # an edit of the original: the same band of it
    else:
        print(f"cut-out SKIPPED: the edit is {ew}x{eh}, not the shape of the photo or of 16:9")
        return None
    s = np.asarray(edit).astype(np.int16)
    h = np.asarray(img).astype(np.int16)
    # magenta: red and blue high, green low
    sky = (s[..., 0] > 200) & (s[..., 2] > 200) & (s[..., 1] < 90)
    if open_sky:
        # open skies: everything above the lowest magenta in each column is sky (for clouds or a
        # haze the edit left unpainted); --extend carries it a few rows further down
        rows = np.arange(H)[:, None]
        low = np.where(sky.any(axis=0), H - 1 - np.argmax(sky[::-1], axis=0), -1)
        sky = rows <= (low + np.where(low >= 0, extend, 0))[None, :]
    share = sky.mean()
    if share < 0.02:
        print("cut-out SKIPPED: the edit has no magenta sky in it")
        return None
    # how well do the two line up where the edit kept the scene?
    err = np.abs(s - h).mean(axis=2)[~sky].mean()
    print(f"sky {share:.0%} of the picture, mismatch outside the sky {err:.1f} (under {limit:g} is fine)")
    if err >= limit:
        print("cut-out SKIPPED: the edit moved or redrew the scene (try the edit again, or --limit "
              "a little higher for a hazy photo once it lines up)")
        return None
    # close pin holes, then soften the edge a little so it does not show a rim over the digits
    a = Image.fromarray(np.where(sky, 0, 255).astype(np.uint8))
    a = a.filter(ImageFilter.MaxFilter(3)).filter(ImageFilter.MinFilter(3)).filter(ImageFilter.GaussianBlur(1.2))
    return np.asarray(a, dtype=np.float64) / 255.0


# ---------------------------------------------------------------- without AI (roliusz's route)

def grown_sky(img, step, far, floor=1.0, spread=6, paint=0.0, thin=0, smooth=0.0, treeline=0.0, fill=0):
    from scipy import ndimage
    a = np.asarray(img.filter(ImageFilter.GaussianBlur(1.6)), dtype=np.float32)
    sky = np.zeros((H, W), bool)
    # the top row: whatever is close to its typical colour
    top = np.median(a[0], axis=0)
    sky[0] = np.linalg.norm(a[0] - top, axis=1) < far
    ref = a[0].copy()   # per column, the sky colour seen most recently
    for y in range(1, min(H, int(floor * H))):   # below the horizon (--floor) nothing is sky
        near = np.linalg.norm(a[y] - a[y - 1], axis=1) < step
        drift = np.linalg.norm(a[y] - ref, axis=1) < far
        ok = near & drift
        row = ok & (sky[y - 1] | np.roll(sky[y - 1], 1) | np.roll(sky[y - 1], -1))
        # spread along the row to neighbours that are just as sky-like (around the sides of things)
        for _ in range(spread):
            grow = ok & (np.roll(row, 1) | np.roll(row, -1))
            if (grow == row).all():
                break
            row = row | grow
        sky[y] = row
        ref[row] = 0.85 * ref[row] + 0.15 * a[y][row]
    # sky walled in by wires or branches: anything above the horizon this close to the sky's
    # colour at the top (--paint) is sky too
    if paint:
        rows = np.arange(H)[:, None] < int(floor * H)
        sky |= rows & (np.linalg.norm(a - top, axis=2) < paint)
    # fog and cloud (--smooth): whatever has no texture and reaches the top is sky too, whatever
    # its colour (the colour test stops where blue sky turns to fog; texture does not change there)
    if smooth:
        g = a.mean(axis=2)
        m = ndimage.uniform_filter(g, 15); m2 = ndimage.uniform_filter(g * g, 15)
        calm = np.sqrt(np.maximum(m2 - m * m, 0)) < smooth
        calm &= np.arange(H)[:, None] < int(floor * H)
        lab, n = ndimage.label(calm | sky)
        if n:
            sky |= np.isin(lab, list(set(np.unique(lab[0])) - {0})) & calm
    # fog down to the trees (--treeline): from the horizon floor, each column that is still sky
    # carries on down through anything without texture (haze, fog) and stops at the first rows
    # with real texture (treetops), so the trees' own ragged edge is the skyline, not a straight line
    if treeline:
        g = a.mean(axis=2)
        m = ndimage.uniform_filter(g, 9); m2 = ndimage.uniform_filter(g * g, 9)
        rough = np.sqrt(np.maximum(m2 - m * m, 0)) > treeline
        # a few textured rows in a row, so a speck in the fog does not stop it
        rough = ndimage.binary_opening(rough, structure=np.ones((5, 1), bool))
        start = max(0, int(floor * H) - 2)
        edge = np.full(W, start)
        # columns that are sky at the floor, or just above it (the colour test can stop a few rows short)
        open_col = sky[max(0, start - 40):start + 1].any(axis=0)
        for x in range(W):
            if not open_col[x]:
                continue
            hit = np.nonzero(rough[start:, x])[0]
            edge[x] = start + (hit[0] if len(hit) else 0)
        edge = ndimage.median_filter(edge, size=5)   # treetops, not single-pixel spikes
        # narrow blocks rising out of the fog are specks of texture in the haze, not trees (crowns
        # are wider): close them over
        edge = ndimage.grey_closing(edge, size=25)
        for x in range(W):
            if open_col[x]:
                top_sky = start - 40 + int(np.nonzero(sky[max(0, start - 40):start + 1, x])[0].max()) if start >= 40 else start
                sky[min(top_sky, start):edge[x], x] = True
    # wires and poles across the sky (--thin): too thin to be scenery, they leave the cut-out
    if thin:
        fg = ~sky
        fg = ndimage.binary_opening(fg, structure=np.ones((1, thin), bool))
        fg = ndimage.binary_opening(fg, structure=np.ones((thin, 1), bool))
        sky = ~fg
    # tidy: no pin-holes in the sky, no stray specks of sky below the skyline
    sky = ndimage.binary_closing(sky, iterations=2, border_value=1)   # past the edge counts as sky, or the top row is lost
    lab, n = ndimage.label(sky)
    if n:
        sky = np.isin(lab, list(set(np.unique(lab[0])) - {0}))
    fg = ~sky
    fg = ndimage.binary_opening(fg, iterations=1)
    # narrow slivers of "sky" cutting down into the scenery (a snow face as smooth as the sky):
    # filled, they are scenery (--fill: widest sliver, in pixels)
    if fill:
        fg = ndimage.binary_closing(fg, structure=np.ones((1, fill), bool), border_value=0) | fg
        fg = ndimage.binary_closing(fg, structure=np.ones((fill // 2, 1), bool), border_value=0) | fg
    # scenery floating in the sky (stars, bits of cloud) is not scenery: only what reaches the
    # bottom of the picture, or the sides, stays
    lab, n = ndimage.label(fg)
    if n:
        edges = set(np.unique(lab[-1])) | set(np.unique(lab[:, 0])) | set(np.unique(lab[:, -1]))
        fg = np.isin(lab, list(edges - {0}))
    return ~fg


def fade(img, sky, floor, rough, hard):
    """Trees in fog (with --treeline): below the horizon floor, in the columns that were sky there,
    the trees grow solid as their texture shows through the mist, so the clock's digits melt into
    the foggy forest instead of meeting a hard edge. Once solid, solid all the way down."""
    from scipy import ndimage
    a = np.asarray(img.filter(ImageFilter.GaussianBlur(1.6)), dtype=np.float32).mean(axis=2)
    m = ndimage.uniform_filter(a, 9); m2 = ndimage.uniform_filter(a * a, 9)
    tex = np.sqrt(np.maximum(m2 - m * m, 0))
    lo, hi = rough * 0.5, rough * 1.8
    soft = ndimage.gaussian_filter(np.clip((tex - lo) / (hi - lo), 0, 1), 5)
    start = max(1, int(floor * H) - 2)
    soft[start:] = np.maximum.accumulate(soft[start:], axis=0)
    open_col = sky[start - 1]
    out = hard.copy()
    region = out[start:, open_col]
    out[start:, open_col] = np.maximum(np.minimum(region, soft[start:, open_col]), 0)
    return out


def refine(img, sky, width):
    """The exact edge (alpha matting): the rough sky mask, shrunk away from its edge on both sides,
    says what is surely sky and surely scenery; in the band between, every pixel's share of scenery
    is worked out from the photo's own colours, so sharp ridges, needle peaks, building corners and
    single trees keep their real outline instead of a smoothed one. None without pymatting."""
    try:
        from pymatting import estimate_alpha_cf
    except ImportError:
        print(f"note: pymatting is not installed, so the edge is the softer one "
              f"(pip install pymatting, or -r {REQUIREMENTS}, for the exact edge)")
        return None
    from scipy import ndimage
    fg = ~sky
    sure_fg = ndimage.binary_erosion(fg, iterations=width, border_value=1)
    sure_sky = ndimage.binary_erosion(sky, iterations=width, border_value=1)
    tri = np.full(sky.shape, 0.5)
    tri[sure_fg] = 1.0; tri[sure_sky] = 0.0
    a = np.clip(estimate_alpha_cf(np.asarray(img, dtype=np.float64) / 255.0, tri), 0, 1)
    # tidy the matte: near-certain values snap, so no faint haze of sky is drawn over the clock
    a[a < 0.15] = 0; a[a > 0.8] = 1
    return a


def soft_edge(sky):
    return np.asarray(Image.fromarray(((~sky) * 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(1.0))) / 255.0


# ---------------------------------------------------------------- the clock's place

ZONES = {"centre": (610, 1310), "left": (96, 796), "right": (1124, 1824)}   # where a ~700 px clock sits


def suggest_layout(img, alpha, side=None):
    """A starting clock layout for this picture: with a cut-out, on the side with the most open sky and high
    enough that the skyline crosses only the lowest third of the digits; without one, centred at 0.14.
    The tint is dark when the picture behind the digits is pale."""
    if alpha is not None:
        solid = alpha >= 0.5
        sky_to = np.where(solid.any(axis=0), np.argmax(solid, axis=0), H)   # per column, where the scenery starts
        low = {k: float(np.median(sky_to[x0:x1])) for k, (x0, x1) in ZONES.items()}
        entry = layout_from_lows(low)
    else:
        entry = build_layout(x="centre", y=0.14)
    return entry


def pale_behind(img, entry):
    """True when the picture under the suggested digits is bright enough that white glass would be faint."""
    x0, x1 = ZONES[entry["x"]]
    y0 = int(entry["y"] * H)
    y1 = min(H, int((entry["y"] + 0.92 * entry["size"]) * H))
    luma = np.asarray(img.convert("L"), dtype=np.float32)[y0:y1, x0:x1]
    return float(luma.mean()) / 255.0 > 0.62


# ---------------------------------------------------------------- the files

def parse_args(argv=None):
    ap = argparse.ArgumentParser(
        description="Turn a photo into a Horizon wallpaper with a glass clock (docs/workflows/make-a-scene.md).")
    ap.add_argument("photo")
    ap.add_argument("name", help="the wallpaper's name: lowercase letters, digits and '-', e.g. 06-valley")
    ap.add_argument("--theme", default=DEFAULT_THEME, help="the theme folder (default themes/horizon)")
    ap.add_argument("--top", type=float, default=0.1, help="where the 16:9 band starts in a taller photo, as a share of its height (0 = the top)")
    ap.add_argument("--preview", nargs="?", const=DEFAULT_PREVIEW, help="also write DIR/NAME-check.jpg, the cut-out over magenta (default build/scenes)")
    ap.add_argument("--bright", type=float, default=1.3, help="how much brighter the clock's frosted fill is than the photo")
    ap.add_argument("--no-config", action="store_true", help="do not touch the theme's config.json; the suggested layout is only printed")
    g = ap.add_argument_group("the clock (a suggestion is made from the picture; these override it in config.json)")
    g.add_argument("--clock-x", choices=SIDES, help="left, centre or right")
    g.add_argument("--clock-y", type=float, help=f"top of the digits, share of the screen height ({Y_RANGE[0]:g} to {Y_RANGE[1]:g})")
    g.add_argument("--clock-size", type=float, help=f"font size, share of the screen height ({SIZE_RANGE[0]:g} to {SIZE_RANGE[1]:g}, 0.37 is the Horizon size)")
    g.add_argument("--clock-inset", type=float, help=f"from the edge for left and right, share of the width ({INSET_RANGE[0]:g} to {INSET_RANGE[1]:g})")
    g.add_argument("--tint", choices=TINTS, help="dark for a smoky clock with dark date text on a pale picture")
    g.add_argument("--date-y", type=float, help=f"top of the date line, share of the screen height ({DATE_Y_RANGE[0]:g} to {DATE_Y_RANGE[1]:g})")
    g.add_argument("--rim", help="colour of the glass rim, #RRGGBB (white by default)")
    g.add_argument("--date-color", help="colour of the date line, #RRGGBB or #AARRGGBB")
    g = ap.add_argument_group("extra depth, optional: a cut-out made from an image model's magenta-sky edit")
    g.add_argument("--sky-edit", help="the same photo with its sky painted flat magenta (#FF00FF): makes the cut-out")
    g.add_argument("--limit", type=float, default=14, help="how far the edit may differ from the photo outside the sky (default 14)")
    g.add_argument("--open-sky", action="store_true", help="everything above the lowest magenta in each column is sky (clouds or haze the edit left)")
    g.add_argument("--extend", type=int, default=0, help="with --open-sky: carry the sky this many pixels further down")
    g = ap.add_argument_group("extra depth, optional: a cut-out found without AI (the sky grown down from the top)")
    g.add_argument("--depth", action="store_true", help="make the cut-out by finding the sky without AI")
    g.add_argument("--step", type=float, default=9.0, help="how much the sky may change from one row to the next and still be sky")
    g.add_argument("--far", type=float, default=46.0, help="how far it may drift from the sky just above (stops it running into a hazy sea)")
    g.add_argument("--floor", type=float, default=1.0, help="the horizon, as a share of the height: nothing below it is sky")
    g.add_argument("--paint", type=float, default=0.0, help="also sky: anything above the horizon this close to the top's colour")
    g.add_argument("--smooth", type=float, default=0.0, help="also sky: texture-free areas (fog, cloud) reaching the top; how calm, e.g. 4")
    g.add_argument("--treeline", type=float, default=0.0, help="from the horizon floor, carry the sky down through fog to the first texture this rough (trees), e.g. 3.5")
    g.add_argument("--thin", type=int, default=0, help="drop anything thinner than this many pixels (wires, poles) from the cut-out")
    g.add_argument("--spread", type=int, default=6, help="how far the sky may spread sideways per row (around wires, posts)")
    g.add_argument("--fill", type=int, default=0, help="fill slivers of sky this narrow that cut down into the scenery, e.g. 40")
    g.add_argument("--band", type=int, default=10, help="how far either side of the rough edge the exact edge is looked for, in pixels")
    g.add_argument("--no-refine", action="store_true", help="skip the exact-edge step (faster, softer edge)")
    return ap.parse_args(argv)


def clock_entry(o, img, alpha):
    """The layout to write: the picture's suggestion, with whatever the command line set on top."""
    entry = suggest_layout(img, alpha)
    if not o.tint and pale_behind(img, entry):
        entry["tint"] = "dark"
    suggested_y = entry["y"]
    side = o.clock_x or entry["x"]
    y = suggested_y if o.clock_y is None else o.clock_y
    return build_layout(
        x=side, y=y, size=o.clock_size if o.clock_size is not None else entry["size"],
        tint=o.tint or entry["tint"], inset=o.clock_inset,
        # the date follows the clock unless it is given
        date_y=o.date_y, rim=o.rim, date_color=o.date_color)


def main(argv=None):
    o = parse_args(argv)
    try:
        check_name(o.name)
        # a bad clock value is refused before anything is written
        build_layout(x=o.clock_x, y=o.clock_y if o.clock_y is not None else 0.14, size=o.clock_size or DEFAULT_SIZE,
                     tint=o.tint, date_y=o.date_y, inset=o.clock_inset, rim=o.rim, date_color=o.date_color)
    except ValueError as err:
        sys.exit(f"make-scene.py: {err}")
    if not os.path.isfile(o.photo):
        sys.exit(f"make-scene.py: no photo at {o.photo}")
    if o.sky_edit and not os.path.isfile(o.sky_edit):
        sys.exit(f"make-scene.py: no edit at {o.sky_edit}")
    if o.sky_edit and o.depth:
        sys.exit("make-scene.py: --sky-edit and --depth are two ways to the same cut-out: pick one")
    theme = os.path.abspath(o.theme)
    if os.path.exists(theme) and not os.path.isdir(theme):
        sys.exit(f"make-scene.py: {o.theme} is a file, not a theme folder")
    config_path = os.path.join(theme, "config.json")
    if not o.no_config and os.path.isfile(config_path):
        # refuse a config we cannot merge into before any picture is written
        try:
            with open(config_path, encoding="utf-8-sig") as f:
                merge_layout(f.read(), o.name, {})
        except (ValueError, OSError) as err:
            sys.exit(f"make-scene.py: {config_path}: {err}")
    load_imaging(o.depth)

    out = os.path.join(theme, "wallpapers")
    os.makedirs(out, exist_ok=True)
    name = os.path.join(out, o.name)
    try:
        src = Image.open(o.photo)
        sw, sh = ImageOps.exif_transpose(src).size
        img = clean(band(src, o.top))
    except OSError as err:
        sys.exit(f"make-scene.py: cannot read {o.photo} as a picture ({err}); a HEIC photo needs pillow-heif")

    q = save_under(img, name + ".jpg", quality=88, optimize=True, progressive=True)
    # the clock's frosted fill: blurred, a little brighter and richer
    glass = img.filter(ImageFilter.GaussianBlur(16))
    glass = ImageEnhance.Color(ImageEnhance.Brightness(glass).enhance(o.bright)).enhance(1.25)
    save_under(clean(glass), name + "_glass.jpg", quality=80, optimize=True)

    a, fg_rgb, wanted = None, img, bool(o.sky_edit or o.depth)
    if o.sky_edit:
        a = magenta_sky(img, o.sky_edit, o.top, sw / sh, o.limit, o.open_sky, o.extend)
    elif o.depth:
        sky = grown_sky(img, o.step, o.far, o.floor, o.spread, o.paint, o.thin, o.smooth, o.treeline, o.fill)
        a = None if o.no_refine else refine(img, sky, o.band)
        if a is not None:
            # the edge's own colours, without the sky mixed in, so no sky-coloured fringe is drawn
            from pymatting import estimate_foreground_ml
            f = estimate_foreground_ml(np.asarray(img, dtype=np.float64) / 255.0, a)
            fg_rgb = Image.fromarray((np.clip(f, 0, 1) * 255).astype(np.uint8))
        else:
            a = soft_edge(sky)
        if o.treeline:
            a = fade(img, sky, o.floor, o.treeline, a)
        share = 1 - a.mean()
        print(f"sky {share:.0%} of the picture (grown from the top edge)")
        if share < 0.03 or share > 0.97:
            print("cut-out SKIPPED: no clear sky found at the top (try --far higher, or the magenta route)")
            a = None

    fg_path = name + "_fg.webp"
    if a is None:
        if os.path.exists(fg_path):
            os.remove(fg_path)   # an old cut-out would no longer match
        print("no cut-out: the clock sits over the whole photo" +
              ("" if wanted else " (add --depth for the skyline in front of it)"))
    else:
        fg = fg_rgb.convert("RGB")
        fg.putalpha(Image.fromarray((a * 255).round().astype(np.uint8)))
        save_under(clean(fg), fg_path, quality=88, method=6)
        print("cut-out written")
        if o.preview:
            os.makedirs(o.preview, exist_ok=True)
            check = Image.new("RGB", (W, H), (255, 0, 255))
            check.paste(fg, (0, 0), fg)
            p = os.path.join(o.preview, o.name + "-check.jpg")
            check.save(p, quality=88)
            print("check image:", p)
    if o.preview and a is None and wanted:
        print("no check image: there is no cut-out to show")

    for f in (name + ".jpg", name + "_glass.jpg") + ((fg_path,) if a is not None else ()):
        print(f"  {f}  ({os.path.getsize(f) // 1024} KB)")

    entry = clock_entry(o, img, a)
    print("\nclock layout:", json.dumps(entry))
    if o.no_config:
        print(f'--no-config: add it to scene_clock_layouts["{o.name}"] in {config_path} yourself')
    else:
        for note in write_config(config_path, o.name, entry, a is not None):
            print("note:", note)
        print(f"merged into {config_path}")
    print(f"push it with: tools\\theme.ps1 -TvHost <tv ip> {theme}")
    return 0


if __name__ == "__main__":
    sys.exit(main())

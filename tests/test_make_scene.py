"""tools/make-scene.py turns a photo into a Horizon wallpaper and merges the clock's layout into a theme's
config.json. The parts that decide whether the theme still works afterwards need no image library, so they
are tested here without numpy or Pillow (CI installs neither: they are in tools/requirements-scenes.txt, not
tools/requirements.txt):

  * the name is one the launcher shows as a wallpaper, not a `_fg` or `_glass` companion (which it hides),
    not "default" (the layout every other wallpaper takes), and one b64push.py will push;
  * every layout value sits inside the range launcher/.../SceneClockLayout.java accepts (outside it the
    launcher silently takes its default, so a clock the owner placed would jump back);
  * the merge keeps every other key of config.json and their order, replaces a layout in place, and puts a
    new scene_clock_layouts after wallpaper_depth;
  * a wrong command line is refused before a file is written, and says what to install when numpy or
    Pillow is missing.

The picture parts (1920x1080 or smaller 16:9, under 300 KB, no metadata, the cut-out) run only when numpy and
Pillow are installed, and are skipped otherwise.

Run from the repository root: python3 -m unittest discover -s tests -v
"""
import importlib.util
import json
import re
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "tools" / "make-scene.py"
HORIZON = ROOT / "themes" / "horizon"
LAYOUT_JAVA = ROOT / "launcher" / "app" / "src" / "main" / "java" / "com" / "gathin" / "tvhome" / "SceneClockLayout.java"

spec = importlib.util.spec_from_file_location("make_scene", SCRIPT)
ms = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ms)

try:
    import numpy  # noqa: F401
    from PIL import Image
    HAVE_IMAGING = True
except ImportError:
    HAVE_IMAGING = False


def run(*args):
    return subprocess.run([sys.executable, str(SCRIPT), *args], capture_output=True, text=True, timeout=120)


class NameTest(unittest.TestCase):
    def test_a_numbered_lowercase_name_is_fine(self):
        for name in ("06-valley", "valley", "a", "07-golden-hour", "x" * 40):
            self.assertEqual(ms.check_name(name), name)

    def test_names_the_launcher_or_the_push_would_mishandle_are_refused(self):
        bad = ["", "Valley", "06 valley", "06_valley", "valley.jpg", "-valley", "../valley", "valley/", "x" * 41,
               "default", "wallpaper", "valley_fg", "valley_glass", "vällåy", None]
        for name in bad:
            with self.subTest(name=name):
                with self.assertRaises(ValueError):
                    ms.check_name(name)

    def test_no_allowed_name_is_read_as_a_companion(self):
        # SceneFiles.isCompanion hides a name whose stem ends _fg or _glass; '_' is not allowed at all
        self.assertIsNone(ms.NAME_RE.fullmatch("scene_fg"))
        self.assertIsNone(ms.NAME_RE.fullmatch("scene_glass"))


class RangeTest(unittest.TestCase):
    def test_the_ranges_are_the_launchers(self):
        java = LAYOUT_JAVA.read_text(encoding="utf-8")
        found = {m.group(1): (float(m.group(2)), float(m.group(3)))
                 for m in re.finditer(r"within\((\w+), ([\d.]+), ([\d.]+), ", java)}
        self.assertEqual(found["y"], ms.Y_RANGE)
        self.assertEqual(found["size"], ms.SIZE_RANGE)
        self.assertEqual(found["inset"], ms.INSET_RANGE)
        self.assertEqual(found["dateY"], ms.DATE_Y_RANGE)

    def test_a_default_layout_is_what_the_horizon_themes_write(self):
        self.assertEqual(ms.build_layout(), {"x": "centre", "y": 0.14, "size": 0.37, "tint": "light", "date_y": 0.09})

    def test_a_side_clock_carries_its_inset_after_x(self):
        entry = ms.build_layout(x="left", y=0.159, size=0.435, tint="dark")
        self.assertEqual(list(entry), ["x", "inset", "y", "size", "tint", "date_y"])
        self.assertEqual(entry["inset"], 0.05)
        self.assertEqual(ms.build_layout(x="right", inset=0.1)["inset"], 0.1)
        self.assertNotIn("inset", ms.build_layout(x="centre", inset=0.1))

    def test_colours_are_written_upper_case_after_the_date(self):
        entry = ms.build_layout(rim="#ffd7e1", date_color="#ebffe8ee")
        self.assertEqual(list(entry)[-2:], ["rim", "date_color"])
        self.assertEqual((entry["rim"], entry["date_color"]), ("#FFD7E1", "#EBFFE8EE"))

    def test_every_value_outside_the_launchers_range_is_refused(self):
        bad = [dict(y=-0.01), dict(y=0.81), dict(size=0.09), dict(size=0.71), dict(inset=0.41, x="left"),
               dict(date_y=0.91), dict(date_y=-0.1), dict(x="middle"), dict(tint="grey"), dict(rim="red"),
               dict(rim="#FFF"), dict(rim="#FFD7E1AA"), dict(date_color="#12345"), dict(y=float("nan")),
               dict(y=True), dict(size="big")]
        for kwargs in bad:
            with self.subTest(kwargs=kwargs):
                with self.assertRaises(ValueError):
                    ms.build_layout(**kwargs)

    def test_the_edges_of_the_ranges_are_fine(self):
        ms.build_layout(y=0.0, size=0.10, date_y=0.0)
        ms.build_layout(y=0.8, size=0.70, date_y=0.9)
        ms.build_layout(x="left", inset=0.40)

    def test_the_date_stays_above_the_clock_inside_its_range(self):
        for y in (0.0, 0.02, 0.12, 0.5, 0.8):
            date_y = ms.build_layout(y=y)["date_y"]
            self.assertGreaterEqual(date_y, 0.02)
            self.assertLessEqual(date_y, 0.9)

    def test_every_shipped_horizon_layout_passes(self):
        layouts = json.loads((HORIZON / "config.json").read_text(encoding="utf-8"))["scene_clock_layouts"]
        for name, entry in layouts.items():
            with self.subTest(name=name):
                again = ms.build_layout(x=entry["x"], y=entry["y"], size=entry["size"], tint=entry["tint"],
                                        date_y=entry["date_y"], inset=entry.get("inset"), rim=entry.get("rim"),
                                        date_color=entry.get("date_color"))
                self.assertEqual(again, entry)


class SuggestionTest(unittest.TestCase):
    def test_the_clock_goes_where_the_sky_is_deepest(self):
        entry = ms.layout_from_lows({"left": 300.0, "centre": 500.0, "right": 700.0})
        self.assertEqual(entry["x"], "right")
        self.assertEqual(entry["inset"], 0.05)

    def test_a_tie_goes_to_the_centre(self):
        self.assertEqual(ms.layout_from_lows({"left": 400.0, "centre": 400.0, "right": 400.0})["x"], "centre")

    def test_the_height_is_kept_inside_what_the_launcher_and_the_digits_allow(self):
        top = ms.layout_from_lows({"left": 0.0, "centre": 0.0, "right": 0.0})
        open_sky = ms.layout_from_lows({"left": 1080.0, "centre": 1080.0, "right": 1080.0})
        self.assertEqual(top["y"], 0.12)
        self.assertEqual(open_sky["y"], round(0.62 - 0.92 * 0.37, 2))


class MergeTest(unittest.TestCase):
    ENTRY = {"x": "centre", "y": 0.2, "size": 0.37, "tint": "light", "date_y": 0.15}

    def test_the_other_keys_and_their_order_stay(self):
        text = (HORIZON / "config.json").read_text(encoding="utf-8")
        merged = ms.merge_layout(text, "06-valley", self.ENTRY)
        before, after = json.loads(text), json.loads(merged)
        self.assertEqual(list(after), list(before))
        for key in before:
            if key != "scene_clock_layouts":
                self.assertEqual(after[key], before[key])
        self.assertEqual(list(after["scene_clock_layouts"]), list(before["scene_clock_layouts"]) + ["06-valley"])
        for name, entry in before["scene_clock_layouts"].items():
            self.assertEqual(after["scene_clock_layouts"][name], entry)
            self.assertEqual(list(after["scene_clock_layouts"][name]), list(entry))
        self.assertEqual(after["scene_clock_layouts"]["06-valley"], self.ENTRY)
        self.assertTrue(merged.endswith("}\n"))

    def test_the_shipped_file_is_unchanged_by_a_round_trip(self):
        text = (HORIZON / "config.json").read_text(encoding="utf-8")
        layouts = json.loads(text)["scene_clock_layouts"]
        name = next(iter(layouts))
        self.assertEqual(ms.merge_layout(text, name, layouts[name]), text)

    def test_a_layout_already_there_is_replaced_where_it_stands(self):
        text = (HORIZON / "config.json").read_text(encoding="utf-8")
        merged = json.loads(ms.merge_layout(text, "03-water", self.ENTRY))
        self.assertEqual(list(merged["scene_clock_layouts"]), list(json.loads(text)["scene_clock_layouts"]))
        self.assertEqual(merged["scene_clock_layouts"]["03-water"], self.ENTRY)

    def test_a_missing_layouts_object_goes_in_after_wallpaper_depth(self):
        text = json.dumps({"columns": 6, "wallpaper_depth": True, "dock_glass": True, "scene_clock": True})
        merged = json.loads(ms.merge_layout(text, "a", self.ENTRY))
        self.assertEqual(list(merged), ["columns", "wallpaper_depth", "scene_clock_layouts", "dock_glass", "scene_clock"])
        self.assertEqual(merged["scene_clock_layouts"], {"a": self.ENTRY})

    def test_without_wallpaper_depth_it_goes_last(self):
        merged = json.loads(ms.merge_layout('{"columns": 6, "dock_glass": true}', "a", self.ENTRY))
        self.assertEqual(list(merged), ["columns", "dock_glass", "scene_clock_layouts"])

    def test_text_that_cannot_be_merged_into_is_refused_untouched(self):
        for text in ("", "{not json", "[1, 2]", '"x"', '{"scene_clock_layouts": [1]}'):
            with self.subTest(text=text):
                with self.assertRaises(ValueError):
                    ms.merge_layout(text, "a", self.ENTRY)

    def test_a_theme_without_the_switches_is_told(self):
        notes = ms.config_notes('{"columns": 6}', True)
        self.assertEqual(len(notes), 2)
        self.assertEqual(ms.config_notes('{"scene_clock": true}', False), [])
        self.assertEqual(ms.config_notes('{"scene_clock": true, "wallpaper_depth": true}', True), [])


class WriteConfigTest(unittest.TestCase):
    ENTRY = MergeTest.ENTRY

    def test_a_config_is_rewritten_in_place_and_keeps_its_line_endings(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "config.json"
            path.write_bytes(b'{\r\n  "scene_clock": true,\r\n  "wallpaper_depth": true,\r\n  "columns": 6\r\n}\r\n')
            ms.write_config(str(path), "a", self.ENTRY, True)
            raw = path.read_bytes()
            self.assertNotIn(b"\n", raw.replace(b"\r\n", b""))
            self.assertEqual(json.loads(raw)["scene_clock_layouts"], {"a": self.ENTRY})
            self.assertEqual(list(Path(tmp).iterdir()), [path], "no temporary file is left behind")

    def test_a_theme_with_no_config_gets_one_the_launcher_can_use(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "config.json"
            notes = ms.write_config(str(path), "a", self.ENTRY, True)
            data = json.loads(path.read_text(encoding="utf-8"))
            self.assertTrue(data["scene_clock"] and data["wallpaper_depth"] and data["wallpaper"])
            self.assertEqual(data["scene_clock_layouts"], {"a": self.ENTRY})
            self.assertEqual(len(notes), 1)

    def test_a_config_that_is_not_json_is_left_as_it_was(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "config.json"
            path.write_text("{oops", encoding="utf-8")
            with self.assertRaises(ValueError):
                ms.write_config(str(path), "a", self.ENTRY, False)
            self.assertEqual(path.read_text(encoding="utf-8"), "{oops")


class CommandLineTest(unittest.TestCase):
    def test_a_bad_name_is_refused_before_anything_is_written(self):
        with tempfile.TemporaryDirectory() as tmp:
            photo = Path(tmp) / "photo.jpg"
            photo.write_bytes(b"not a picture")
            theme = Path(tmp) / "theme"
            for name in ("Bad_Name", "valley_fg", "default"):
                with self.subTest(name=name):
                    r = run(str(photo), name, "--theme", str(theme))
                    self.assertEqual(r.returncode, 1, r.stderr)
                    self.assertIn(name, r.stderr)
                    self.assertFalse(theme.exists())

    def test_a_clock_value_outside_the_range_is_refused_before_anything_is_written(self):
        with tempfile.TemporaryDirectory() as tmp:
            photo = Path(tmp) / "photo.jpg"
            photo.write_bytes(b"x")
            theme = Path(tmp) / "theme"
            r = run(str(photo), "06-valley", "--theme", str(theme), "--clock-y", "0.95")
            self.assertEqual(r.returncode, 1)
            self.assertIn("range the launcher accepts", r.stderr)
            self.assertFalse(theme.exists())

    def test_a_missing_photo_and_a_theme_that_is_a_file_are_named(self):
        with tempfile.TemporaryDirectory() as tmp:
            r = run(str(Path(tmp) / "nope.jpg"), "06-valley")
            self.assertEqual(r.returncode, 1)
            self.assertIn("nope.jpg", r.stderr)
            photo = Path(tmp) / "p.jpg"
            photo.write_bytes(b"x")
            afile = Path(tmp) / "afile"
            afile.write_text("x")
            r = run(str(photo), "06-valley", "--theme", str(afile))
            self.assertEqual(r.returncode, 1)
            self.assertIn("not a theme folder", r.stderr)

    def test_a_config_that_is_not_json_stops_the_run_before_any_picture(self):
        with tempfile.TemporaryDirectory() as tmp:
            photo = Path(tmp) / "p.jpg"
            photo.write_bytes(b"x")
            theme = Path(tmp) / "theme"
            theme.mkdir()
            (theme / "config.json").write_text("{oops", encoding="utf-8")
            r = run(str(photo), "06-valley", "--theme", str(theme))
            self.assertEqual(r.returncode, 1)
            self.assertIn("not valid JSON", r.stderr)
            self.assertFalse((theme / "wallpapers").exists())

    def test_missing_libraries_are_named_with_the_file_to_install_from(self):
        # run with numpy and Pillow made unimportable, whether or not they are installed here
        code = ("import sys, importlib.util\n"
                "for m in ('numpy', 'PIL'): sys.modules[m] = None\n"
                f"spec = importlib.util.spec_from_file_location('ms', {str(SCRIPT)!r})\n"
                "ms = importlib.util.module_from_spec(spec); spec.loader.exec_module(ms)\n"
                "ms.load_imaging(False)\n")
        r = subprocess.run([sys.executable, "-c", code], capture_output=True, text=True, timeout=60)
        self.assertEqual(r.returncode, 1)
        self.assertIn("numpy and Pillow", r.stderr)
        self.assertIn("requirements-scenes.txt", r.stderr)

    def test_the_optional_libraries_are_not_in_the_ci_requirements(self):
        base = (ROOT / "tools" / "requirements.txt").read_text(encoding="utf-8").lower()
        for lib in ("numpy", "pillow", "scipy", "pymatting"):
            self.assertNotIn(lib, base)
        scenes = (ROOT / "tools" / "requirements-scenes.txt").read_text(encoding="utf-8").lower()
        for lib in ("numpy", "pillow", "scipy", "pymatting"):
            self.assertIn(lib, scenes)


@unittest.skipUnless(HAVE_IMAGING, "numpy and Pillow are not installed (tools/requirements-scenes.txt)")
class PictureTest(unittest.TestCase):
    def make_photo(self, path, size=(2400, 1500)):
        import numpy as np
        w, h = size
        sky = np.linspace([70, 130, 220], [190, 215, 245], h // 2)[:, None, :].repeat(w, axis=1)
        ground = np.full((h - h // 2, w, 3), [40, 90, 40], float)
        ground += np.random.RandomState(1).normal(0, 12, ground.shape)
        pixels = np.clip(np.vstack([sky, ground]), 0, 255).astype("uint8")
        img = Image.fromarray(pixels)
        exif = Image.Exif()
        exif[0x010F] = "SecretCamera"
        img.save(path, quality=92, exif=exif)

    def test_a_photo_becomes_a_wallpaper_its_glass_and_a_layout(self):
        with tempfile.TemporaryDirectory() as tmp:
            theme = Path(tmp) / "my-theme"
            theme.mkdir()
            (theme / "config.json").write_text((HORIZON / "config.json").read_text(encoding="utf-8"), encoding="utf-8")
            photo = Path(tmp) / "photo.jpg"
            self.make_photo(photo)
            r = run(str(photo), "06-valley", "--theme", str(theme))
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            wall, glass = theme / "wallpapers" / "06-valley.jpg", theme / "wallpapers" / "06-valley_glass.jpg"
            for f in (wall, glass):
                with Image.open(f) as im:
                    self.assertEqual(im.size, (1920, 1080))
                    self.assertNotIn(b"SecretCamera", f.read_bytes())
                    self.assertFalse(im.getexif())
                self.assertLessEqual(f.stat().st_size, 300 * 1024)
            self.assertFalse((theme / "wallpapers" / "06-valley_fg.webp").exists())
            layouts = json.loads((theme / "config.json").read_text(encoding="utf-8"))["scene_clock_layouts"]
            self.assertIn("06-valley", layouts)
            self.assertEqual(layouts["06-valley"]["x"], "centre")
            self.assertEqual(len(layouts), 6)

    def test_no_config_leaves_the_config_alone_and_the_clock_flags_set_the_layout(self):
        with tempfile.TemporaryDirectory() as tmp:
            theme = Path(tmp) / "t"
            theme.mkdir()
            original = (HORIZON / "config.json").read_bytes()
            (theme / "config.json").write_bytes(original)
            photo = Path(tmp) / "photo.png"
            self.make_photo(photo, (1920, 1080))
            r = run(str(photo), "07-two", "--theme", str(theme), "--no-config", "--clock-x", "left", "--clock-y", "0.2")
            self.assertEqual(r.returncode, 0, r.stderr)
            self.assertEqual((theme / "config.json").read_bytes(), original)
            self.assertIn('"x": "left"', r.stdout)
            r = run(str(photo), "07-two", "--theme", str(theme), "--clock-x", "right", "--clock-y", "0.2", "--tint", "dark")
            self.assertEqual(r.returncode, 0, r.stderr)
            entry = json.loads((theme / "config.json").read_text(encoding="utf-8"))["scene_clock_layouts"]["07-two"]
            self.assertEqual((entry["x"], entry["inset"], entry["y"], entry["tint"], entry["date_y"]),
                             ("right", 0.05, 0.2, "dark", 0.15))

    def test_a_wide_photo_is_cut_from_the_middle_and_a_tall_one_by_top(self):
        with tempfile.TemporaryDirectory() as tmp:
            ms.load_imaging(False)
            wide = Image.new("RGB", (3000, 1000), (10, 20, 30))
            self.assertEqual(ms.band(wide, 0.1).size, (1920, 1080))
            tall = Image.new("RGB", (1000, 2000))
            for y in range(2000):
                tall.paste((y % 256, 0, 0), (0, y, 1000, y + 1))
            top0, top1 = ms.band(tall, 0.0), ms.band(tall, 1.0)
            self.assertEqual(top0.size, (1920, 1080))
            self.assertNotEqual(top0.getpixel((10, 10)), top1.getpixel((10, 10)))


if __name__ == "__main__":
    unittest.main()

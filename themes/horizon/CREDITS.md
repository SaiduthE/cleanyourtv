Wallpapers, glass fills and cut-outs from couch-tv by Seagey (https://github.com/Seagey/couch-tv, MIT licence), `tv-home/web/backgrounds/`.

The five scenes were generated once, from text only, with an AI image model by couch-tv's author: they are not real
places. Its `CREDITS.md` says of them "Use them however you like", and the files that go with them are made from those
same scenes, so the same terms apply (MIT licence: the scenes, the glass fills and the cut-outs). Nothing is generated
or downloaded on the TV; these are plain image files, resized and recompressed where noted.

The wallpapers (shown in this order):

- 01-night.jpg: Night, a peak over a lake at dusk (couch-tv scene "night"), MIT
- 02-golden.jpg: Golden hour, a volcano (couch-tv scene "golden"), MIT
- 03-water.jpg: Waterline (couch-tv scene "water"), MIT; recompressed to fit the 300 KB rule
- 04-fog.jpg: Fog over misty hills (couch-tv scene "fog"), MIT
- 05-dawn.jpg: Rainforest dawn (couch-tv scene "dawn"), MIT

Each wallpaper's blurred, brightened copy, which fills the clock's digits like frosted glass (couch-tv "scene-NAME-glass"),
MIT:

- 01-night_glass.jpg
- 02-golden_glass.jpg
- 03-water_glass.jpg
- 04-fog_glass.jpg
- 05-dawn_glass.jpg

The scenery cut out of a wallpaper with the sky transparent (couch-tv "scene-NAME-fg"), drawn over the clock by the
`wallpaper_depth` key so the peaks and hills overlap the bottom of the digits, MIT. The waterline scene has none:

- 01-night_fg.webp
- 02-golden_fg.webp
- 04-fog_fg.webp
- 05-dawn_fg.webp

To add a scene of your own from any photo, offline and with no AI, see `tools/make-scene.md`
(`tools/make-scene.py`, adapted from couch-tv's `make-scene.py`, MIT, with the no-AI cut-out route by roliusz).

These `_glass` and `_fg` files are companions, not wallpapers: the launcher never shows them on their own.

`screenshot.jpg` is a preview composed from the night scene with the clock drawn by a script, not a capture from a TV.

The clock's digits use Antonio Light, a subset of Antonio (SIL Open Font License 1.1), bundled in the launcher; see
`launcher/FONT-LICENSE-OFL-ANTONIO.txt`.

These files keep couch-tv's MIT licence, which is also the repository's.

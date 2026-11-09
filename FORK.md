# Shield fork: remote buttons, styles, widgets and a Top Shelf

A fork of the [Clean Your TV](README.md) launcher, tuned on an NVIDIA SHIELD TV Pro (2019) at 4K,
Android TV 11. It is a heavy restyle and larger than upstream's APK size gate, so it lives here and
not in a pull request. Build it from source: `launcher/build-and-install.ps1` (or `.sh`) as in the
main README.

![Home on the Shield](docs/shield-home.jpg)

## What it adds

- **Remote buttons**: map any button to an app or action as press, hold or double press, set on the
  TV (`KeyMap`, `RemoteKeysActivity`, with the key helper capturing the button).
- **Style presets** ("Clean Look") that change colours, wallpaper, dock and columns together, and a
  grouped settings side panel with a popup picker per row; hold OK on a row for its tip.
- **Move mode**: pick up an app tile and move it with the D-pad.
- **A Top Shelf** above the dock: the focused app's own "continue watching" artwork when the
  Continue watching add-on is installed, otherwise a slow photo or wallpaper backdrop.
- **Widgets**: a weather chip and a brightness control that sit beside the clock or in the top bar,
  each supplied by an add-on in `addons/`. The launcher asks neither for a permission.
- **Top bar**: a red/green icon-only VPN button, a Discover pill that opens the Continue watching
  hub, and an icon, text or both style for the top-bar buttons.

## Permissions and network

- **The launcher** has no `INTERNET` permission and makes no network request, as upstream. Its
  manifest differs from upstream only by `<queries>` entries for the three add-ons below and new
  internal settings screens. It adds no `uses-permission`.
- **Add-ons are optional**, each its own app, and none is built into the launcher:
  - `addons/continue-watching` (`INTERNET`, `READ_TV_LISTINGS`): your Jellyfin server, if you add
    one, the picture addresses other apps publish, and, for the Top Shelf photos,
    `commons.wikimedia.org` and `upload.wikimedia.org` only. No account or identifier is sent.
  - `addons/weather` (`INTERNET`, `RECEIVE_BOOT_COMPLETED`): `api.open-meteo.com`,
    `geocoding-api.open-meteo.com` and `air-quality-api.open-meteo.com`. The city name is shared
    with the launcher only if you turn on "Show place on Home" (off by default).
  - `addons/brightness` (`SYSTEM_ALERT_WINDOW`, `FOREGROUND_SERVICE`, `RECEIVE_BOOT_COMPLETED`):
    no network.
- Nothing is collected beyond the above. The key helper is an accessibility service that stays off
  until you turn it on, and reads only the buttons you mapped.

## Defaults

The defaults in `config.xml` are upstream's. Everything above is chosen on the TV or by a theme.

## Tested on

NVIDIA SHIELD TV Pro (2019), Android TV 11, SHIELD Experience 9.2.4. Not tested on other TVs.
Launcher unit tests and `lintRelease` pass; the release APK is about 1 MB.

# Continue watching

Everything you are partway through, in one place on the TV: the titles apps publish to Android TV's **Watch Next**
row (Netflix, Prime Video, Plex and the like, where they publish any) and your own **Jellyfin** server, with the
show's own picture, a resume bar and the episode ("S2 · E4 · 23 min left"). OK starts the title in its app. For the
[Clean Your TV](../../README.md) launcher it also declares a [module card](../../docs/modules.md) and serves a
**Top Shelf feed** (below) so the launcher can show the same titles with real artwork.

- **Sources are managed on the TV**, with the remote only, and show **only what you added**. **Sources** (top
  right of the page) is a grid of tiles, one per source, each with the app's icon (its banner when that is large
  enough; never enlarged past 1.5 times), its name and a status dot: **N rows** (green), **No rows found**, an
  error (red: "Sign in again", "Not reachable", "Needs access") or **Off**. The last tile is **+ Add source**;
  with nothing added it is the only tile, under a one-line hint. Header buttons: **Allow Watch Next access** (until
  granted), **Rescan apps**, and the **Top Shelf feed** switch (off: the launcher gets nothing and the cached
  pictures are deleted).
  - **Add source** opens a picker: a **Jellyfin server** tile (the form for the address, the user and a password
    or an API key; **Test** signs in without saving, **Save** keeps it), then the installed apps that publish
    Watch Next rows or preview channels, the ones with the most rows first. Apps already added are dimmed and
    marked **Added**. **Show all apps**, the last tile, lists the other apps with a TV launcher icon, read only
    when you ask: an app added from there (Netflix, say) simply reports **No rows found** if it publishes
    nothing this app can read. Choosing an app adds it, with its Watch Next rows on and its channels as below, and
    returns to the grid with focus on the new tile.
  - **OK on a tile** opens a small popup on it (flips above or to the left near a screen edge; Back closes it and
    returns to the tile): the source's **On/Off** (hides all of it), its **channels** with row count and
    On/Off (scrolls inside the popup past five), for Jellyfin **Resume**, **Next up**, **Test** and **Edit
    sign-in**, and **Remove source**, which asks once on the same row ("Remove it? Remove / Keep").
  - **Channels** (Plex, Netflix, ...): many apps publish their "Continue watching" row as a *preview channel*, not
    as Watch Next rows, and the stock Android TV home shows those. Each channel is listed in the popup with its
    row count ("12 rows", "3 of 5" when some have no title or link, or "empty"). Channels named like *Continue
    watching*, *Resume*, *Watch Next*, *Up next*, *Keep watching* or *Recently watched* (and a few translations)
    are **on** by default; every other channel (Recommended, Trending, ...) is **off** until you switch it on.
  - **Upgrading**: the first start after the update adds, by itself, every source that was showing: Jellyfin if
    it is set up, each app that published rows and was not switched off, each app with a channel that was on. The
    list is saved in the preferences in the order added (Jellyfin's sign-in stays in the encrypted store).
  - Scrolling is one 150-200 ms decelerating glide that centres the focused row; the platform's own jump is off,
    so the highlight and the scroll move together.
- **The page is grouped by app**: one section per app that has anything to resume, each a header (the app's icon at
  its own size, name, title count and the row it came from, such as "Continue watching") over a sideways row of
  cards (picture, title, episode, resume bar). The app watched most recently comes first, then the bigger rows;
  apps without items are not shown, and with none at all the page says "Add a source to see what to resume".
  Left/Right move inside a row, Up/Down between sections, landing on the column you were on (a short row holds
  you at its last card and a longer one returns you to where you started); Up from the first row reaches Refresh
  and Sources. Each move is one glide (the row sideways, the page vertically). Pictures load only for cards on or
  near the screen and are let go when far away; at most 8 sections of 20 cards.
- **First run.** When Watch Next access is granted, a probe reads every app's rows once and remembers which
  packages publish any (counts only, in `adb logcat -s CWProbe`: no titles, no addresses). Some apps publish
  nothing readable to a sideloaded app (see [`docs/watch-next.md`](../../docs/watch-next.md)); Sources shows
  that per app instead of failing silently.
- **Changes** to a switch, an app or the feed apply at once; a new or changed server is read straight away. The
  page reads again when it is opened and more than two minutes have passed, or on **Refresh**.

## Permissions

| Permission | Why |
|---|---|
| `READ_TV_LISTINGS` | Reads the Watch Next rows of other apps. Ask for it from **Sources > Allow Watch Next access**, or grant it: `adb shell pm grant com.cleanlook.continuewatching android.permission.READ_TV_LISTINGS`. |
| `INTERNET` | Your Jellyfin server, and the pictures: the server's own, and the picture addresses other apps put in their Watch Next rows (those are plain image downloads from the app's own servers, sent with no account, cookie or identifier). Also the [Photos](#photos): `https` to `commons.wikimedia.org` and `upload.wikimedia.org` only. |
| `ACCESS_NETWORK_STATE` | Whether the TV is connected, and whether the connection is unmetered, before the Photos are refreshed. |

Also: `usesCleartextTraffic`, because a Jellyfin server on the home network is normally `http://`; a `<queries>`
entry, because Android 11 hides other apps otherwise (it lists apps that have a TV launcher icon, and the Jellyfin
client). No analytics, no ads, no boot receiver, no service of its own that stays running: only the system's
three-day job for the [Photos](#photos), which ends when it has checked. Credentials: the password is only sent to
your server to sign in and is never stored; the access token (or API key) is stored encrypted with a key in the
Android Keystore (`SecureStore`). Addresses, tokens and titles are never written to logcat. Backups are off.

## Top Shelf feed for the launcher

A read-only `ContentProvider`, authority **`com.cleanlook.continuewatching.shelf`**.

**Who may read it.** The provider is exported with no manifest permission, and checks the caller itself: this app,
the Clean Your TV launcher (`com.gathin.tvhome`), or any app signed with the same certificate as this one (so a
launcher built under another application id works when both were built and signed on the same machine). Anyone
else gets a `SecurityException` from `query` and `openFile`. A launcher that queries it on Android 11 must name it
in its manifest or the provider is invisible:

```xml
<queries><provider android:authorities="com.cleanlook.continuewatching.shelf" /></queries>
```

**Rows.** `content://com.cleanlook.continuewatching.shelf/items` (optional `?pkg=<source package>`): up to 12 rows,
at most 3 per source app, titles partway through first and each group newest first (then by channel weight, for
channel rows, which carry no watch time), one row per title per app. With `?pkg=` the app's items are all
returned (up to 10), so a launcher can show the first as "continue" and the next two as "up next". The projection
is ignored; every column is returned.

| Column | Type | Meaning |
|---|---|---|
| `_id` | long | Row number in this answer. |
| `item_id` | string | Stable id (`wn:...` Watch Next, `jf:...` Jellyfin). |
| `package` | string | The source app's package. |
| `kind` | string | `resume` (partway through) or `next` (the next episode). |
| `title` | string | The show, or the film. |
| `subtitle` | string | `S2 · E4`, the episode's name, or the year; may be empty. |
| `progress` | int | Whole percent, 0 to 100; 0 for `next` and when the app did not say (channel rows: from last playback position and duration when both exist). A launcher hides the bar at 0. |
| `remaining_ms` | long | Time left, or -1. |
| `updated_ms` | long | When it was last watched (approximate for `next`). |
| `image` | string | The best single picture: `backdrop`, else `poster`; or null. |
| `backdrop`, `poster`, `logo` | string | `content://` URIs of cached artwork, or null. Backdrop: JPEG at most 960 px wide. Poster: JPEG, 400 px. Logo: PNG with transparency, 480 px (Jellyfin only; Watch Next has none). |
| `intent_uri` | string | An `intent:` URI that opens the title in its app, or the app itself when the title has no link. Start it with `Intent.parseUri(uri, Intent.URI_INTENT_SCHEME)` and `startActivity` with `FLAG_ACTIVITY_NEW_TASK`. |
| `source_label` | string | The source app's name. |
| `fetched_ms` | long | When the data was last read (0 if never). |

The cursor has a notification URI: it changes after every refresh, switch or Remove. Asking at most every ten
minutes (Home resume is fine) also starts a refresh in this app's process, so the launcher needs no timer.

**Artwork.** `content://com.cleanlook.continuewatching.shelf/img/<name>.jpg|.png` is served by `openFile` (read
only), only for the names the rows return. The largest suitable picture is used (a landscape thumbnail or poster for the
backdrop, a portrait poster for the poster), never enlarged; a backdrop under 320 px wide is left out (null) so the
launcher does not stretch it. Channel rows are merged with Watch Next rows of the same app (the same link or
title is shown once) and with the other sources. The pictures are the show's own artwork, downscaled and cached on
disk by this app while it has network, so the launcher needs none. Nothing is blurred; if a row has no picture the
column is null and the launcher should show a plain surface. An app that publishes a wide image as its poster has
it used as the backdrop too.

**Start the app.** Alternatively send `com.cleanlook.continuewatching.action.RESUME` to `HubActivity`; with an
int extra `accent` it tints the resume bars, and with a string extra `item_id` it starts that title at once and
shows nothing.

## Photos

The launcher's Top Shelf shows a slow, full-bleed photo behind the cards, like Windows Spotlight or a browser's
new-tab photos. This app supplies the photos (and only this app touches the internet for them): scenic pictures from
**Wikimedia Commons** featured-picture categories, cached on the TV.

- **Source.** The MediaWiki API on `commons.wikimedia.org` (no key, no account) lists the files of the categories
  *Featured pictures of landscapes, mountains, coasts, lakes, forests, clouds, sunsets*; the 1920 px copy of each
  chosen picture comes from `upload.wikimedia.org`. Nothing else is contacted: every address, and every redirect,
  must be `https` to one of those two hosts. The `User-Agent` is the generic `CleanTV-Photos/1.0 (Android TV)`;
  no account, cookie, device id or location is sent.
- **What is kept.** JPEG only, at least 2500 px wide on the original, shape 1.5 to 2.2 times wider than tall, and
  a free licence only: CC BY, CC BY-SA, CC0 or public domain (anything non-commercial, no-derivatives, fair use or
  unlabelled is dropped). Pictures whose file name or Commons categories point to maps, diagrams, paintings,
  portraits, people, trains and railways, buildings or interiors are skipped. Each photo is stored as the 1920 px
  JPEG (normally under 1 MB; one over 3 MB is refused).
- **Cache.** 15 photos, about 12 to 14 MB, in this app's private files. A refresh fetches 5 new ones (or enough to
  fill an emptier cache) and drops the 5 oldest, the oldest also going first if the size limit is hit.
- **When.** The first photos are fetched as soon as the add-on is opened, or the launcher first asks for the photos
  with an empty cache (a quick fill of up to 15, each photo available as soon as it arrives). After that at most
  every 3 days, on an unmetered, connected network (a `JobScheduler` job every 3 days, plus the checks when the add-on
  opens and when the launcher asks). **Launcher settings > Top Shelf > Refresh photos** fetches at once on any connection.
- **On the TV.** The photo settings live in the launcher: **Launcher settings > Top Shelf** has **Photos** (On/Off,
  default On; Off deletes the photos and the launcher uses its own wallpapers), **Photo themes** (a switch per
  category; at least one stays on), **Refresh photos** and the plain status line on its right ("12 photos, updated
  today", "Refreshing…", "No network", "Timed out"). This app only has the feed; the Sources screen no longer lists it.
- **Credit.** Each photo carries a plain-text credit, "Author — Licence", at most 80 characters, made from the
  Commons author and licence fields with the markup removed, so the launcher can show it.

**Provider.** Same authority and the same caller check as the rest of the feed.

`content://com.cleanlook.continuewatching.shelf/photos`: newest first, every column returned.

| Column | Type | Meaning |
|---|---|---|
| `_id` | long | Row number in this answer. |
| `photo_uri` | string | `content://com.cleanlook.continuewatching.shelf/photo/<name>.jpg` |
| `width`, `height` | int | Pixels of the cached JPEG (1920 wide). |
| `credit` | string | "Author — Licence", plain text, at most 80 characters. |

`content://com.cleanlook.continuewatching.shelf/photo/<name>.jpg` is one JPEG, opened read only. A name is served
only if it is 16 lower-case hex digits and `.jpg` **and** is in the cache folder's listing; anything else
(`..`, a path, another extension, a temporary file) is a `FileNotFoundException`. The cursor of `/photos` notifies
when a photo arrives or leaves. An empty answer means "use your own wallpapers" (the switch is off, or nothing is
cached yet).

**Photo settings (`call()`).** The launcher has no network and cannot read this app's preferences, so Launcher
settings > Top Shelf reads and writes the photo settings with `ContentResolver.call(content://com.cleanlook.continuewatching.shelf, method, null, extras)`.
Same caller check as above (anyone else gets a `SecurityException`); an unknown method answers `null`. Every answer is
a Bundle with the current settings: `ok` (boolean; false comes with `error`: `at_least_one`, `off` or `bad_request`),
`enabled`, `category_keys` / `category_labels` (all categories), `categories_on` (keys), `count`, `refreshing` and
`status` (the plain line: "12 photos, updated today", "Refreshing…", "No network", "Off").

| Method | Extras | Does |
|---|---|---|
| `photos_get_settings`, `photos_status` | none | Reads the current state. |
| `photos_set_enabled` | `enabled` (boolean) | Photos on (a first fetch starts) or off (the photos are deleted). |
| `photos_set_categories` | `categories_on` (String[] of keys) | Sets the categories; none left on is refused (`at_least_one`). |
| `photos_refresh` | none | Starts a refresh on any connection and returns at once; poll `photos_status` until `refreshing` is false. |

## Jellyfin deep link

The title is started with `org.jellyfin.androidtv.ui.startup.StartupActivity` and the extras `ItemId` and
`ItemIsUserView`, as the Jellyfin client's own Watch Next rows do. This is not a documented API and has not been
verified on a TV by this project: if the client refuses it, the app itself opens and the page says so.

## Build

JDK 17 and Android SDK platform 34, as for the launcher. In this folder:

```bash
./gradlew testDebugUnitTest assembleDebug     # gradlew.bat on Windows
./gradlew assembleRelease                     # signed with the debug key, like the launcher
```

The unit tests are plain JVM tests of the added-sources model (migration, order, picker, status, popup placement), the Watch Next filter, the Jellyfin parser, the merge, the shelf rows, the
caller check and the intent policy. The only third-party code is test-only (JUnit, and `org.json` because the
stubs in `android.jar` throw on the JVM).

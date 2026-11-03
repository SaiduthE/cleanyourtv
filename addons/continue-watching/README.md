# Continue watching

Everything you are partway through, in one place on the TV: the titles apps publish to Android TV's **Watch Next**
row (Netflix, Prime Video, Plex and the like, where they publish any) and your own **Jellyfin** server, with the
show's own picture, a resume bar and the episode ("S2 · E4 · 23 min left"). OK starts the title in its app. For the
[Clean Your TV](../../README.md) launcher it also declares a [module card](../../docs/modules.md) and serves a
**Top Shelf feed** (below) so the launcher can show the same titles with real artwork.

- **Sources are managed on the TV**, with the remote only. **Sources** (top right of the page) lists:
  - **Watch Next apps**: every app with a TV launcher icon, with how many rows it publishes and an On/Off switch.
    An app that publishes nothing this app can read says **No rows found**; OK on it scans again and says so
    plainly. The app switch covers its Watch Next rows only; **channels** (below) have their own.
  - **Channels** (Plex, Netflix, ...): many apps publish their "Continue watching" row as a *preview channel*, not
    as Watch Next rows, and the stock Android TV home shows those. Under each such app, every channel is listed
    with its name, its row count ("12 rows", "3 of 5 rows can be shown" when some have no title or link, or "empty
    right now") and its own On/Off switch (OK toggles). Channels named like *Continue watching*, *Resume*, *Watch
    Next*, *Up next*, *Keep watching* or *Recently watched* (and a few translations) are **on** by default;
    every other channel (Recommended, Trending, ...) is **off** until you switch it on. The choice is saved per
    app and channel name.
  - **Servers**: **Add Jellyfin server** opens a form for the address, the user and a password or an API key.
    **Test** signs in without saving and says what it found ("Connected to Home as sai. 3 titles in progress."
    or why not); **Save** keeps it. Once added: Edit, Resume and Next up switches, **Test Jellyfin**, and Remove
    (press OK twice).
  - **Launcher**: switch the Top Shelf feed off, and the launcher gets nothing and the cached pictures are deleted.
- **First run.** When Watch Next access is granted, a probe reads every app's rows once and remembers which
  packages publish any (counts only, in `adb logcat -s CWProbe`: no titles, no addresses). Some apps publish
  nothing readable to a sideloaded app (see [`docs/watch-next.md`](../../docs/watch-next.md)); Sources shows
  that per app instead of failing silently.
- **Changes** to a switch, an app or the feed apply at once; a new or changed server is read straight away. The
  page reads again when it is opened and more than two minutes have passed, or on **Refresh**.

## Permissions

| Permission | Why |
|---|---|
| `READ_TV_LISTINGS` | Reads the Watch Next rows of other apps. Ask for it from **Sources > Watch Next access**, or grant it: `adb shell pm grant com.cleanlook.continuewatching android.permission.READ_TV_LISTINGS`. |
| `INTERNET` | Your Jellyfin server, and the pictures: the server's own, and the picture addresses other apps put in their Watch Next rows (those are plain image downloads from the app's own servers, sent with no account, cookie or identifier). |

Also: `usesCleartextTraffic`, because a Jellyfin server on the home network is normally `http://`; a `<queries>`
entry, because Android 11 hides other apps otherwise (it lists apps that have a TV launcher icon, and the Jellyfin
client). No analytics, no ads, no background service, no boot receiver. Credentials: the password is only sent to
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

The unit tests are plain JVM tests of the Watch Next filter, the Jellyfin parser, the merge, the shelf rows, the
caller check and the intent policy. The only third-party code is test-only (JUnit, and `org.json` because the
stubs in `android.jar` throw on the JVM).

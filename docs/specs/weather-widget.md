# Weather widget, spec 1

The [Weather add-on](../../addons/weather/README.md) publishes the weather it already reads for its own
page, so the launcher can show it next to the clock and on the screensaver. The launcher has no
network permission and never goes online; the add-on does the reading and the launcher only queries a
read-only content provider.

This document is the contract between the add-on (the publisher) and the launcher (the consumer). It follows
the style of the [module card](card.md) and the game-day provider in [`docs/modules.md`](../modules.md).

## Provider

| | |
| :- | :- |
| Package | `io.github.sumyabazar1.weather` |
| Authority | `io.github.sumyabazar1.weather.widget` (the package name plus `.widget`) |
| Exported | yes, no permission: the data is public weather for one city and nothing private |
| Access | read-only: `insert`, `update` and `delete` throw `UnsupportedOperationException` |
| Settings action | `io.github.sumyabazar1.weather.action.SETTINGS` |

The provider answers from a snapshot saved on the TV. A query never touches the network and never blocks on it.
After every refresh the add-on calls `ContentResolver.notifyChange` on both URIs below, and also when the owner
changes the units, "Show place on Home" or "Animated weather art". The returned cursors have a notification URI
set, so `registerContentObserver` on either URI works.

Without a chosen city, or with a snapshot of a city the owner has since replaced, both URIs return an empty
cursor.

## `content://io.github.sumyabazar1.weather.widget/current`

One row (or none).

| Column | Type | What |
| :- | :- | :- |
| `spec` | integer | `1`. Ignore the row for any other value. |
| `updated_ms` | integer | Epoch milliseconds when the weather was read from Open-Meteo. |
| `place` | text | The city's name, at most 40 characters, no control characters. Empty if unknown. |
| `temp_c` | real | Air temperature now in degrees Celsius, one decimal. |
| `feels_c` | real | Feels-like temperature, degrees Celsius. |
| `high_c`, `low_c` | real | Today's high and low, degrees Celsius. |
| `code` | integer | WMO weather code, 0 to 99. Rain near freezing is already shown as snow by the add-on's wet-bulb rule. |
| `is_day` | integer | `1` day, `0` night at the city. |
| `humidity` | integer | Relative humidity, 0 to 100 %. |
| `wind_kmh` | integer | Wind speed, km/h. |
| `aqi` | integer | US air quality index, `-1` when unknown. |
| `unit` | text | `c` or `f`: the owner's choice in the add-on's settings. The numbers above are always Celsius; the consumer converts for display. |
| `show_place` | integer | `1` if the owner wants the place name shown on Home, `0` if not (the default). |
| `animated` | integer | `1` if the owner wants the animated weather art (the default), `0` for still art. |

## `content://io.github.sumyabazar1.weather.widget/daily`

Up to 7 rows, today first, then the days after it, sorted by date. Fewer or none when the read gave fewer.

| Column | Type | What |
| :- | :- | :- |
| `date` | text | `yyyy-MM-dd`, the city's local date. |
| `code` | integer | WMO weather code, 0 to 99. |
| `high_c`, `low_c` | real | Degrees Celsius. |
| `precip_pct` | integer | Chance of precipitation that day, 0 to 100, `-1` when the model gave none. |

A query may name a subset of these columns in the projection; an unknown column name throws
`IllegalArgumentException`. Any other path throws `IllegalArgumentException`. Selection and sort arguments are ignored.
No column ever carries an intent, a URI or a link.

## Opening the settings

The launcher starts the add-on's settings with a fixed action and the package, never with a component or anything
read from the provider:

```java
Intent i = new Intent("io.github.sumyabazar1.weather.action.SETTINGS")
        .setPackage("io.github.sumyabazar1.weather")
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
```

The add-on opens straight to its settings dialog (units, forecast length, "Show place on Home", "Animated weather
art", "Refresh every 15/30/60 min") and closes with it, so Back returns to the launcher. With no city chosen yet it
asks for one first.

## Consumer rules

- **Check the owner's opt-in your own way** (a config key naming the package, as with `game_day`). Take the
  authority from the package you were told to trust, not from a provider that claims it: confirm with
  `PackageManager.resolveContentProvider` that the authority belongs to that package.
- **Package visibility on Android 11 and later.** The launcher's manifest needs
  `<queries><provider android:authorities="io.github.sumyabazar1.weather.widget" /></queries>` (and
  `<queries><intent><action android:name="io.github.sumyabazar1.weather.action.SETTINGS" /></intent></queries>`
  to resolve the settings action) or the provider and activity are invisible to it.
- **Validate everything.** Read `spec` first. Treat a row as invalid, and show nothing, if a value is outside its
  range: `code` 0 to 99, `is_day` and the two flags 0 or 1, `humidity` 0 to 100, `unit` not `c` or `f`, a `date`
  that does not parse, `temp_c` outside -90 to 60 or a high below a low by more than rounding. Draw `place` as plain
  text, one line, ellipsised.
- **Staleness.** Data older than **90 minutes** (`now - updated_ms`) is stale: show it dimmed or without the
  temperature, as you see fit. Data older than **6 hours** is absent: show no weather at all. An `updated_ms` more
  than a few minutes in the future is absent too.
- **Query off the main thread**, when Home comes back and on each change notification; watch only while Home or the
  screensaver is on screen. A query that finds data older than 35 minutes makes the add-on schedule one refresh (at
  most one in ten minutes), so an empty or old answer is followed by a change notification when fresh data arrives.
- **Never write.** There is nothing to write, and no permission is needed to read.

## Publisher behaviour

- A persisted periodic job (default every 30 minutes, 15 or 60 in settings, network required) reads Open-Meteo
  for the chosen city and replaces the snapshot. A boot receiver puts the job back after a restart or an update.
- Choosing another city or changing the units notifies readers at once and starts a one-shot refresh.
- A failed read keeps the last snapshot (its `updated_ms` ages, so the consumer's staleness rules apply) and
  is retried by the system with backoff.
- The add-on's `INTERNET` permission is used by the job and the page only, never on a binder thread.

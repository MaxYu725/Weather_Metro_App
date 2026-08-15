# WeatherMetroApp

A native Android weather app for Hong Kong, rebuilt around the Windows Phone
Metro design language. The Hong Kong Observatory (HKO) is the primary weather
and warning source; Open-Meteo is used only for clearly labelled local hourly
estimates that HKO does not publish at the same granularity.

## Product highlights

- Full-screen, edge-to-edge interface with no app bar, logo, or clock region.
- Endless four-page Pivot: `current`, `forecast`, `tools`, `settings`.
- Expandable tiles automatically move into view for direct reading.
- Stable seeded geometric tile backgrounds: expansion reveals the previously
  clipped remainder without moving the pattern.
- Warning and special-weather-tip tiles are laid out four per row; the selected
  detail opens full-width directly below its own row.
- Current conditions include HKO observation, rainfall, UV and visibility plus
  clearly marked secondary pressure, wind, dew point, and feels-like estimates.
- Sunrise, transit, sunset, moon times, calculated phase/illumination, and the
  nearest available HKO tide station.
- Precise Android fused location, Hong Kong street/district reverse geocoding,
  nearest HKO observation station, and nearest tide station selection.
- Native Tools modules for point rainfall, observed Radar, two-hour SWIRLS
  forecast, and multi-agency tropical-cyclone Live tracks from HKO/CMA/JMA/CWA.
- MapLibre-native Radar, two-hour Forecast and tropical-cyclone rendering with
  independent tool lifecycle, cache and refresh state; no WebView/Leaflet
  runtime is embedded.
- Accent colour, text scaling, geometric pattern strength, motion, contrast,
  precise-location and notification settings.
- Offline atomic cache and stale-data indication, with independent Rain and
  Storm caches alongside the normal weather cache.
- FCM HTTP v1 alert delivery backed by a five-minute Apps Script monitor.

## Integration status

The current production native Tools scope is **complete**. P1 code/static closure
and the final continuous cross-tool real-device smoke both passed on the merged
P1 baseline.

Completed production scope:

- Point rainfall: integrated with host-location independence, stale-data
  retention and foreground-only refresh behavior.
- Radar: MapLibre production path validated on real devices. Production always
  opens in LIVE mode; TEST transport remains internal and is not exposed by the
  normal Tools surface.
- Two-hour Forecast: **P0 complete**. MapLibre is the production-visible
  renderer and has passed repeated playback, pan/zoom, background/resume,
  real-rain raster and timeline-following validation. Canvas remains only as a
  hidden reference/safety implementation.
- Storm Live: HKO/CMA/JMA/CWA MapLibre path validated on real devices with
  independent source state and last-good fallback.

Storm Archive is intentionally **deferred** and is not part of the completed
production scope. Storm-Track is still accumulating historical storm/advisory
records and the Archive feature has not yet completed formal standalone
functional/real-device validation.

See [docs/TOOLS_TODO.md](docs/TOOLS_TODO.md) for deferred work and the completed
P0/P1 record.

## Architecture

The old WebView/HTML shell has been replaced with Kotlin and Jetpack Compose.
The app contains no embedded server credential and does not perform periodic
work every five minutes on the handset; the server monitor does that work and
FCM wakes subscribed devices efficiently.

Rain and Storm are native tool modules with independent repositories and load
state. A normal weather-source failure must not make `tools` or `settings`
unavailable. Fullscreen tools temporarily hide the host Pivot and stop their
disposable requests/animation when hidden.

Weather Metro owns one Android location pipeline. The host resolves one
`LocationInfo`, exposes it independently to native Tools, and passes the same
resolved location into the normal Weather refresh. A normal weather transport
failure therefore does not remove the Tools host location.

```text
app/
  data/          HKO/Open-Meteo plus Rain/Storm transport, cache, location and settings
  domain/        UI-independent weather, Rain and Storm models
  notification/ FCM service and Android notification channels
  ui/            Compose Pivot, native tool surfaces, Metro tiles and theme
backend/apps-script/
  Code.gs        HKO state monitor and FCM HTTP v1 sender
```

Detailed decisions are in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md), the
visual/interaction contract is in [docs/DESIGN_SPEC.md](docs/DESIGN_SPEC.md),
and native tool ownership is documented in
[docs/TOOLS_INTEGRATION.md](docs/TOOLS_INTEGRATION.md).

## Local build

Requirements:

- JDK 17
- Android SDK 37
- The checked-in Gradle wrapper
- `app/google-services.json` for Firebase project `weathermetropush`

On Windows:

```powershell
./gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. Every push and
pull request runs the same validation in GitHub Actions and uploads the debug APK
as a workflow artifact.

## FCM alert monitor

Follow [backend/apps-script/README.md](backend/apps-script/README.md). Firebase
service-account fields belong only in Apps Script Properties. Never put a
private key in this repository, `google-services.json`, Gradle files, or app
resources.

## Signed releases

The release build reads signing material only from environment variables. The
`Signed Android release` workflow expects these GitHub Actions secrets:

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

It produces a signed APK, an AAB, and SHA-256 checksums. Keep the original
keystore and passwords in a separate password-manager-backed archive; Android
updates must use the same signing identity.

## Data and privacy

See [DATA_SOURCES.md](DATA_SOURCES.md) for field provenance and [PRIVACY.md](PRIVACY.md)
for on-device data handling. This project is not an official HKO application;
always follow the current warning text issued by the Hong Kong Observatory.

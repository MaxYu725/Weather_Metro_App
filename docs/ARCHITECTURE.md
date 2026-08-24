# Architecture

## Data flow

1. `WeatherViewModel` exposes settings and a single `WeatherLoadState`.
2. `WeatherRepository` obtains a precise location when permitted, otherwise the
   HKO headquarters fallback, then asks `HkoClient` for a snapshot.
3. `HkoClient` requests independent HKO datasets concurrently. Open-Meteo fills
   only local fields unavailable from HKO and never replaces an available HKO
   observation.
4. A successful raw response bundle is committed through Android `AtomicFile`.
   A network failure returns that cache with a visible stale indicator.
5. Compose renders immutable domain models. UI settings are isolated in
   `SharedPreferences` and exposed as a `StateFlow`.

## Location model

Android's fused provider requests a fresh high-accuracy reading. The platform
geocoder resolves a Hong Kong street or feature label and district. The app then
normalises English/Chinese district output and calculates the nearest supported
HKO observation and tide stations with great-circle distance. Location is not
uploaded, logged, or included in FCM subscriptions.

## Alert truth and notification delivery

The app derives active alert tiles from HKO `warnsum`; `warningInfo` supplies the
long text and `swt` supplies special tips. Rows marked `CANCEL` are excluded.

Apps Script performs the same active-state normalisation every minute. IDs are
warning-code based (tips use a content digest), while fingerprints contain the
stable warning code, title, and text. A script lock prevents overlapping
executions. New deployments emit alerts already in force; V3/V4 state migrates
without replaying unchanged alerts.

Alert baselines and issue/update/cancel events use per-item Script Properties,
so no value exceeds Apps Script's 9 KB quota. Events enter the outbox before FCM
delivery and failed sends remain queued with exponential backoff. Messages use
high Android priority, a 24-hour TTL, no collapse key, deterministic event IDs,
and byte-bounded text so topic payloads remain below the FCM limit.

Android validates each data message, commits its byte-bounded preview to a local
inbox, and posts it immediately. WorkManager then reconciles the complete Google
Sheets journal row and updates the same stable notification ID without alerting
twice. The client compares distinct FCM-cached and build-configured Apps Script
URLs and selects the response with the newest server cursor, so a deleted or
frozen deployment is self-healed. Permission- or channel-blocked events remain
pending and are replayed when the app resumes after access is restored. Posted
event IDs provide durable deduplication for server retries. If FCM reports that
pending messages were deleted, the app schedules full journal reconciliation.
Users can open Android's app notification settings directly from the settings page.

This is an at-least-once, compensating design rather than an absolute delivery
guarantee: FCM topics, Android user controls, device force-stop, and OEM power
management remain outside the app's control. A stronger service would add a
server event journal, per-installation cursors/acknowledgements, and client
reconciliation over an authenticated API.

## Security boundaries

- `google-services.json` identifies the Firebase Android client; it is not a
  server credential and cannot authorise FCM sends.
- A Firebase service-account private key exists only as an Apps Script Property.
- Release signing material exists only in a local environment or GitHub Secrets.
- Backups are disabled to avoid exporting the cached location/weather bundle.
- Network calls are HTTPS and no app WebView or JavaScript bridge remains.

## Build and delivery

The project uses AGP 9, the built-in Kotlin toolchain, JDK 17 and the Compose
compiler plugin. GitHub Actions validates the Gradle wrapper, runs tests/lint,
and uploads an APK. A separate manual/tag workflow requires signing secrets and
also produces an Android App Bundle and checksums.

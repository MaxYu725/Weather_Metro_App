# Weather Metro — Rain / Storm tool integration roadmap

Status: **Phase 1A Rain data foundation**  
Weather Metro baseline: `2f6904697edc6541f62cc489b597627ff178953f`  
Rain-Track reference baseline: `2a3a62c75c397564f1b46e2e8cd86db313bd5b7a`  
Storm-Track reference baseline: `b03d16149a33928a49790b0d8308dd31e40b1ed4`

## Goal

Replace the current `tools` Pivot page of external HKO browser shortcuts with native Weather Metro tool modules backed by the already-verified Rain-Track and Storm-Track production services.

The host remains Kotlin + Jetpack Compose. No standalone PWA shell is embedded into the app.

Rain-Track is integrated as **independent reusable capabilities**, not as one mandatory `RainTrackScreen`. Weather Metro can open point rainfall, Radar, two-hour Forecast, or Rain settings directly according to context.

## Current host replacement point

Weather Metro keeps five top-level Pivot pages:

```text
current / hourly / forecast / tools / settings
```

`ToolsScreen` currently opens official HKO webpages through `ACTION_VIEW`. This screen is the integration replacement point.

Target navigation is capability-first rather than app-inside-app navigation:

```text
CURRENT
  └── rainfall tile / detail
       └── RainPointPanel

TOOLS
  └── ToolsHome
       ├── Point rainfall ─────────── RainPointPanel
       ├── Radar ─────────────────── RainMapScreen(RADAR)
       ├── 2-hour Forecast ───────── RainMapScreen(FORECAST)
       └── Tropical cyclone ──────── Storm module

SETTINGS
  └── Rain preferences when persistent host-level options are needed
```

A compact Radar / Forecast switch may exist inside the map surface for convenience, but users do not have to enter a Rain home screen before reaching either mode.

## Rain component boundary

The standalone Rain-Track PWA currently combines a map, bottom sheet, Radar, two-hour Forecast and settings. Weather Metro must decompose those responsibilities.

Target native composition:

```text
Rain domain/data
  RainRepository
  RainTrackClient
  point forecast
  SWIRLS forecast
  radar metadata/images
  separate Rain cache

Rain UI components
  RainSummary
  RainPointPanel
  RainTimeline
  RainMapScreen(initialMode)
  RadarOverlay / RadarControls
  ForecastOverlay / ForecastControls
  RainSettingsSheet or host settings rows
```

Required behavior:

- `RainPointPanel` can render without any map;
- Radar can open directly without first rendering point forecast;
- Forecast can open directly without first rendering Radar;
- tapping/selecting a location on either map may reuse `RainPointPanel`;
- Radar settings are visible only for Radar mode;
- Forecast playback/opacity settings are visible only for Forecast mode;
- persistent settings belong to Weather Metro storage, not browser localStorage;
- the standalone Rain-Track bottom-sheet lifecycle is a reference behavior, not a UI shell to copy wholesale.

## Backend ownership

### Rain

```text
https://radar.max-yu.workers.dev
```

Weather Metro consumes public runtime APIs for:

- capabilities;
- point forecast;
- SWIRLS 16-frame forecast;
- full nowcast fallback;
- radar metadata;
- radar image proxy.

### Storm

```text
https://storm.max-yu.workers.dev
```

Weather Metro consumes public runtime APIs for:

- per-agency live transport behind `StormService`;
- CWA live data;
- storm/advisory history;
- health/diagnostics where useful.

Do not merge either Worker into `Weather_Metro_App/backend` during initial integration.

## Security invariants

- HTTPS only.
- No Cloudflare deployment credential in the Android app.
- No Storm admin/D1/R2/CWA secret in the Android app.
- No UI component may build arbitrary Worker proxy URLs.
- No general-purpose WebView or JavaScript bridge.
- Rain radar images must use the Rain Worker image proxy.
- Production origins live in one host-side registry: `ToolEndpoints`.

## Host architecture

```text
app/src/main/java/com/weather/metro/
  data/
    tools/             production origins / endpoint builders
    rain/              transport, parser, native cache, repository
    storm/             transport, parser, native cache
  domain/
    rain/              immutable Rain models
    storm/             immutable Storm models
  ui/
    tools/             ToolsHome and internal navigation
    rain/              reusable Compose Rain surfaces
    storm/             Compose Storm surfaces
```

The existing normal weather `WeatherLoadState` must not become a giant shared state for tool modules. Rain and Storm keep independent service/load state so one tool failure cannot break current/hourly/forecast pages or the other tool.

## Rain integration contract

Authoritative source document: `MaxYu725/Rain-Track/INTEGRATION.md`.

Key invariants:

- point rainfall uses existing Weather Metro location coordinates;
- preferred Forecast timeline is SWIRLS frames `0..15`;
- 6 minutes is the valid-time cadence, while each value is `mm / 30 min` accumulation;
- frames are `121 × 121 = 14,641` cells;
- lazy-load selected/next frames instead of eagerly fetching all 16;
- `/api/rain/nowcast` remains a four-period fallback;
- nowcast fallback grid reconstruction uses observed unique axes, never one artificial minimum step;
- Radar = observation, Forecast = future valid time;
- Radar and Forecast map modes remain mutually exclusive;
- Radar settings and Forecast playback/opacity settings remain separate;
- hidden/background Rain surfaces stop playback/animation and disposable requests.

## Storm integration contract

Authoritative source document: `MaxYu725/Storm-Track/docs/WEATHER_APP_INTEGRATION.md`.

Key invariants:

- HKO/CMA/JMA/CWA stay independent official sources;
- one agency failure does not fail the whole Live result;
- Archive keeps backend storm/advisory IDs;
- old requests cannot overwrite a newer selected storm/advisory;
- Archive replay stops when hidden;
- browser IndexedDB/localStorage are not host persistence;
- native map ownership is separate from data refresh;
- no old historical Storm Worker source may be redeployed from the standalone repository.

## Cache strategy

Weather Metro already has an offline atomic cache for normal weather. Tool integration preserves the same product principle but keeps separate namespaces:

```text
weather cache
rain cache
storm live cache
storm archive cache
```

Shared rules:

- last successful data can render first;
- a failed refresh never erases good cache;
- stale state is visible;
- point cache is request-scoped and must not be reused for a different coordinate/radius;
- cache clear eventually clears all host-owned tool caches;
- browser PWA cache keys are never imported into Android.

## Lifecycle strategy

Because Pivot pages can remain composed while off-screen, visibility must be driven by selected host/tool state rather than Composable existence.

When a tool is hidden:

- stop autoplay/replay/animation;
- cancel disposable requests;
- keep only state needed for quick return;
- do not poll in the background.

When restored:

- resume rendering;
- refresh only when stale/explicitly requested;
- do not create another map instance merely because the surface became visible again.

## Native rendering strategy

Final state is native Compose + native raster/map rendering.

Rain reference pipeline:

```text
Worker JSON → validated grid/frame → native bitmap/raster → map overlay
```

Storm reference pipeline:

```text
Worker JSON → normalized agency tracks → native vector/map layers
```

The map library is deliberately not selected in Phase 1. Library choice is made after data/domain integration so rendering technology does not dictate the service contract.

## Implementation checkpoints

### Phase 0A — source contract freeze — COMPLETE

- refreshed Rain integration contract to Worker v2.5.0 / SWIRLS 16-frame behavior;
- refreshed Storm integration baseline to current release candidate;
- confirmed Weather Metro `ToolsScreen` as the host replacement point.

### Phase 0B — host skeleton — COMPLETE

- one production endpoint registry;
- unit tests for fixed public endpoint construction;
- no Tools UI/runtime change.

### Phase 1A — Rain point data foundation

- Rain capabilities parser/client;
- point forecast parser/client;
- immutable Rain domain models independent of Compose;
- dedicated `RainRepository` and namespaced native cache;
- request-scoped cache fallback;
- fixtures and fail-closed parser tests;
- no Tools UI/runtime change.

### Phase 1B — Rain host state wiring

- create Rain-specific load/state owner;
- reuse Weather Metro location coordinates;
- cancellation/refresh ownership;
- expose point forecast to multiple future UI surfaces without coupling them to a Rain home screen.

### Phase 2 — Rain forecast map data

- SWIRLS frame parser;
- run/frame cache and cancellation;
- 60-second-class bounded frame request budget suitable for Worker rollover recovery;
- nowcast observed-axis fallback parser/regression test;
- no map UI yet.

### Phase 3 — ToolsHome + Rain point UI

- replace external shortcut list with native ToolsHome;
- reusable `RainPointPanel` / summary surface;
- direct entry points rather than a mandatory Rain home screen;
- internal back behavior;
- keep Storm entry present but gated until its service is ready.

### Phase 4 — Rain map rendering

- direct `RADAR` and `FORECAST` map entry modes;
- native raster overlay;
- 16-frame timeline/autoplay;
- Radar / Forecast mutually exclusive modes;
- independent settings;
- optional point panel overlay/sheet;
- lifecycle/rotation/background regression.

### Phase 5 — Storm data foundation

- history service/domain/cache;
- per-agency live service/domain;
- partial-source failure semantics;
- fixtures and cancellation tests.

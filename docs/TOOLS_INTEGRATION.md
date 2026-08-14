# Weather Metro — Rain / Storm tool integration roadmap

Status: **Phase 0B host preparation**  
Weather Metro baseline: `32fc4dd08344b1eb3c59e84c4423bc7ee476d557`  
Rain-Track reference baseline: `b762b27ac428b5369b53ba2b6c5ee7b7d65dfc9d`  
Storm-Track reference baseline: `bf6bb3616d861c62f156bc8a77e67a8c404487f8`

## Goal

Replace the current `tools` Pivot page of external HKO browser shortcuts with native Weather Metro tool modules backed by the already-verified Rain-Track and Storm-Track production services.

The host remains Kotlin + Jetpack Compose. No standalone PWA shell is embedded into the app.

## Current host replacement point

Weather Metro currently keeps five top-level Pivot pages:

```text
current / hourly / forecast / tools / settings
```

`ToolsScreen` currently opens official HKO webpages through `ACTION_VIEW`. This screen is the integration replacement point.

Target navigation:

```text
TOOLS
  └── ToolsHome
       ├── Rain
       │    ├── Point forecast
       │    └── Map: Radar / 2-hour Forecast
       └── Storm
            ├── Live
            └── Archive
```

Rain and Storm are internal states of `TOOLS`, not new top-level Pivot pages.

## Backend ownership

### Rain

```text
https://radar.max-yu.workers.dev
```

Weather Metro will consume public runtime APIs for:

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

Weather Metro will consume public runtime APIs for:

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

Planned structure:

```text
app/src/main/java/com/weather/metro/
  data/
    tools/             production origins / endpoint builders
    rain/              transport, parser, native cache
    storm/             transport, parser, native cache
  domain/
    rain/              immutable Rain models
    storm/             immutable Storm models
  ui/
    tools/             ToolsHome and internal navigation
    rain/              Compose Rain surfaces
    storm/             Compose Storm surfaces
```

The existing normal weather `WeatherLoadState` should not become a giant shared state for tool modules. Rain and Storm should have independent service/load states so one tool failure cannot break current/hourly/forecast pages or the other tool.

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

Weather Metro already has an offline atomic cache for normal weather. Tool integration should preserve the same product principle but keep separate namespaces:

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

The map library is deliberately not selected in Phase 0. Library choice will be made after data/domain integration so rendering technology does not dictate the service contract.

## Implementation checkpoints

### Phase 0A — source contract freeze

- refresh Rain integration contract to Worker v2.5.0 / SWIRLS 16-frame behavior;
- refresh Storm integration baseline to current release candidate;
- confirm Weather Metro `ToolsScreen` as the host replacement point.

### Phase 0B — host skeleton

- add one production endpoint registry;
- add unit tests for fixed public endpoint construction;
- no Tools UI/runtime change yet.

### Phase 1 — Rain data foundation

- Rain capabilities parser/service;
- point forecast parser/service;
- domain models;
- fixtures and fail-closed parser tests;
- native cache boundary.

### Phase 2 — Rain forecast map data

- SWIRLS frame parser;
- run/frame cache and cancellation;
- 60-second-class bounded frame request budget suitable for Worker rollover recovery;
- nowcast observed-axis fallback parser/regression test;
- no map UI yet.

### Phase 3 — ToolsHome + Rain UI

- replace external shortcut list with native ToolsHome;
- Rain point forecast surface;
- internal back behavior;
- keep Storm entry present but gated until its service is ready.

### Phase 4 — Rain map rendering

- native raster overlay;
- 16-frame timeline/autoplay;
- Radar / Forecast mutually exclusive modes;
- independent settings;
- lifecycle/rotation/background regression.

### Phase 5 — Storm data foundation

- history service/domain/cache;
- per-agency live service/domain;
- partial-source failure semantics;
- fixtures and cancellation tests.

### Phase 6 — Storm native UI/map

- Live source status/list;
- track rendering;
- multi-agency comparison;
- Archive list/detail/replay;
- lifecycle/map ownership regression.

### Phase 7 — Tools consolidation

- remove superseded external HKO shortcut tiles where native functionality exists;
- keep only browser links that still provide unique value;
- unified cache clear/diagnostics;
- final mobile/PWA-reference comparison and release regression.

## Rollback rule

Each integration phase must be independently revertible. Do not delete the current Tools external shortcuts until the corresponding native module has passed device regression.

Rain-Track and Storm-Track remain deployable standalone references throughout integration.

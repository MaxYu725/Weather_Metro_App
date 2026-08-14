# Weather Metro — native Tools integration contract

Status: **Rain + Radar + 2-hour Forecast + Storm Live integrated; IR1 host review**  
Weather Metro review baseline: `f3ceeeea5c1d99f0c9e20203b7c6b7a1ec1faae1`  
Rain-Track reference: `MaxYu725/Rain-Track`  
Storm-Track reference: `MaxYu725/Storm-Track`

## 1. Product ownership

Weather Metro is the native Android host. Rain-Track and Storm-Track are source/reference products; their runtime capabilities are integrated as native Kotlin/Compose modules rather than embedded PWAs.

The current host Pivot is:

```text
current / forecast / tools / settings
```

`tools` is discovery/home only. Entering a native tool hides the host Pivot and gives that tool the full available app surface.

Current ToolsHome:

```text
ToolsHome
  ├── Point rainfall
  ├── Radar
  ├── 2-hour Forecast
  ├── 2-hour Forecast · MapLibre experiment/reference
  └── Tropical cyclone Live
```

Storm Archive is not yet integrated into the native host.

## 2. Host state independence

Normal weather, Rain, Radar and Storm do not share one giant load state.

```text
WeatherViewModel
  └── normal HKO/Open-Meteo weather state

RainHostViewModel
  ├── point rainfall
  └── 2-hour Forecast

RainRadarHostViewModel
  └── observed Radar

StormHostViewModel
  └── HKO/CMA/JMA/CWA Live tracks
```

A failure or refresh in one product must not fail another product.

`TOOLS` and `SETTINGS` are host-owned surfaces and must remain usable even while normal weather is loading or has failed. Normal weather loading/error UI belongs only to `CURRENT` and `FORECAST`.

## 3. Location ownership

Weather Metro owns the only Android location pipeline:

```text
LocationRepository
  → WeatherRepository
  → WeatherSnapshot.location
  → RainHostViewModel.bindHostLocation(...)
  → RainRadarHostViewModel.bindHostLocation(...)
  → StormHostViewModel.bindHostLocation(...)
```

Tool modules must not create another fused-location owner or independently request location permission.

Point rainfall depends on host coordinates. Radar and Storm may continue to operate when a host coordinate is temporarily unavailable. A host location change invalidates only location-bound point rainfall state.

## 4. Backend boundaries

### Rain / Radar

Production origin:

```text
https://radar.max-yu.workers.dev
```

Weather Metro consumes documented runtime APIs for:

- Rain capabilities and point forecast;
- SWIRLS two-hour forecast timeline/frames;
- nowcast fallback where applicable;
- Radar contract/timeline;
- Radar image/test-image routes.

Radar image access remains Worker-owned. No arbitrary image/proxy URL builder is exposed to Compose.

### Storm

Production origin:

```text
https://storm.max-yu.workers.dev
```

Weather Metro consumes documented public runtime APIs for:

- HKO/CMA/JMA/CWA Live transport behind `StormService`;
- CWA official API data through the Worker;
- History storm/advisory APIs for the future Archive phase;
- health/probes only where diagnostics require them.

The Android app must never contain Cloudflare admin credentials, D1/R2 credentials, CWA authorization secrets, or unrestricted Worker proxy construction.

## 5. Security invariants

- HTTPS only.
- No WebView or JavaScript bridge for native Tools.
- No Leaflet runtime inside Weather Metro.
- Production origins live in `ToolEndpoints`.
- Rain and Storm Workers remain separate production services.
- HKO/CMA/JMA/CWA remain independently identified sources.
- An external-agency forecast must never be presented as HKO output.

## 6. Rain semantics

Point rainfall, Radar and two-hour Forecast are separate capabilities.

### Point rainfall

- uses Weather Metro host location;
- supports the native request-scoped radius choices;
- same-request refresh may retain last-good data and mark it stale;
- hidden point requests are cancellable.

### Radar

Radar means **observed/past scans**, not future forecast.

Current native production behavior includes:

- MapLibre raster rendering;
- Worker-defined range/height/mode contract;
- LIVE and deterministic TEST modes;
- 6-minute observation cadence;
- timeline/playback speed/opacity controls;
- latest-frame hold;
- foreground-only LIVE auto refresh;
- freshness status;
- bounded recent/adjacent frame prefetch;
- last-good retention and generation guards.

Radar settings remain independent from Forecast settings.

### 2-hour Forecast

Forecast means **future valid-time SWIRLS data**.

Current contract:

- preferred SWIRLS timeline contains 16 forecast frames;
- cadence is 6 minutes;
- each grid value remains a 30-minute rainfall accumulation value;
- frame/run rollover is guarded;
- loading/prefetch is cancellable;
- Canvas remains the reference/safety renderer;
- MapLibre Forecast remains available as the validated experimental/reference renderer during the final renderer consolidation decision.

Do not silently reinterpret Radar scans as future forecast or merge their clocks/settings.

## 7. Storm Live semantics

Storm Live currently supports HKO, CMA, JMA and CWA as independent official sources.

Native state behavior:

- each agency loads independently;
- a failed source does not block successful sources;
- each agency has an independent last-success snapshot;
- successful empty/no-active-storm results are valid cache entries;
- stale/failed refresh does not erase last-good tracks;
- manual Update forces all four agencies;
- foreground automatic policy refreshes only sources that need it;
- successful snapshots are treated as fresh for 15 minutes;
- failed/no-snapshot sources use a 5-minute retry backoff;
- hidden/background Storm surfaces do not poll;
- cancellation/generation guards block late state/cache publication.

MapLibre rendering preserves agency identity:

- analysis path = solid;
- forecast path = dashed;
- track lines use agency colours;
- analysis/forecast nodes use Storm-Track intensity colours;
- probability circles and quadrant wind geometry render only when supplied;
- source toggles affect visibility without re-fetching;
- selected point details resolve from the current ViewModel snapshot and fail closed when invalidated.

## 8. Fullscreen lifecycle

ToolsHome is not fullscreen. Point rainfall, Radar, Forecast and Storm are fullscreen internal destinations.

While a fullscreen tool is active:

- host Pivot chrome is hidden;
- Pivot swipe navigation is disabled;
- Android Back returns to ToolsHome;
- the active tool owns back/refresh/status/contextual controls;
- leaving the tool cancels disposable requests and stops playback/animation;
- hidden/background tools do not poll;
- state/cache needed for quick return may remain in the ViewModel.

Map ownership must not be recreated merely because data refreshes.

## 9. Cache ownership

Separate namespaces are intentional:

```text
normal weather cache
rain cache
radar preferences / transient image prefetch
storm live cache
future storm archive cache
```

Shared rules:

- last-good data can render first;
- failed refresh never deletes good cached data;
- stale state remains explicit;
- cache keys preserve upstream/backend identity;
- browser PWA IndexedDB/localStorage keys are not imported;
- Weather Metro Settings → Clear cache clears normal Weather, Rain and Storm host-owned caches.

User preferences such as Radar opacity/speed/product are settings, not disposable cache.

## 10. Renderer status

Validated native map direction is MapLibre.

Current state:

- Radar: MapLibre production path validated on real device;
- Storm Live: MapLibre production path validated on real device;
- Forecast: Canvas reference renderer plus validated MapLibre experimental/reference renderer.

Do not remove the Canvas Forecast safety/reference path as part of unrelated integration maintenance. Renderer consolidation is a separate checkpoint with explicit regression testing.

## 11. Completed checkpoints

Rain integration completed through native point/Forecast state, fullscreen Tools presentation, MapLibre feasibility and Radar production hardening.

Radar native milestones completed through M1D:

- contract/data layer;
- MapLibre raster prototype;
- controls/playback/persistence;
- LIVE refresh/freshness/prefetch hardening.

Storm Live native milestones completed through S1F:

- S1A History/domain foundation;
- S1B HKO/CMA/JMA/CWA Live adapters;
- S1C native state/cache/fullscreen host;
- S1C1 Android XML parser compatibility hotfix;
- S1D MapLibre tracks/probability/wind geometry;
- S1E point interaction;
- S1E1 anchored detail popup + intensity colours;
- S1F foreground freshness/cancellation production hardening.

All current Storm Live visual and lifecycle checkpoints have passed real-device validation.

## 12. Current integration review — IR1

IR1 audits the whole Tools host without assuming previous phases are sufficient.

First identified host defect:

- `WeatherMetroRoot` previously gated every Pivot page behind normal `WeatherLoadState`, so a normal-weather loading/error state could make otherwise independent Tools/Settings unavailable.

IR1 rule:

- only `CURRENT` and `FORECAST` require normal weather data;
- `TOOLS` and `SETTINGS` render independently;
- normal weather refresh progress is not shown as if it were a Tools/Settings refresh.

IR1 does not change Rain/Storm parsers, Worker contracts, map geometry or validated Storm/Radar visual behavior.

## 13. Next work after IR1

Recommended order:

1. finish ToolsHome/product-surface review and remove only clearly obsolete development residue;
2. run cross-tool lifecycle/back/cache regression on a real device;
3. decide Forecast renderer consolidation separately;
4. then begin native Storm Archive list/detail/advisory playback.

Storm Archive must reuse the existing normalized History APIs and must not reintroduce Leaflet/WebView or a second app-inside-app shell.

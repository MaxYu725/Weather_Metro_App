# Weather Metro — native Tools integration contract

Status: **integration in progress — not closed**  
Current review baseline: `main` after IR3  
Rain-Track reference: `MaxYu725/Rain-Track`  
Storm-Track reference: `MaxYu725/Storm-Track`

## 1. Product ownership

Weather Metro is the native Android host. Rain-Track and Storm-Track are
source/reference products; their capabilities are integrated as native
Kotlin/Compose modules rather than embedded PWAs.

The host Pivot is:

```text
current / forecast / tools / settings
```

`tools` is discovery/home only. Entering a native tool hides the host Pivot and
gives that tool the full available app surface.

Production ToolsHome exposes:

```text
ToolsHome
  ├── Point rainfall
  ├── Radar
  ├── 2-hour Forecast
  └── Tropical cyclone Live
```

There is no production A/B renderer card.

## 2. Integration completion status

The overall native Tools integration is **not complete**.

Current state:

- Point rainfall: integrated; continue regression/hardening work as needed.
- Radar: MapLibre production path validated on real devices.
- 2-hour Forecast: MapLibre is the production-visible renderer, but Forecast
  still requires further product/real-device refinement before closure.
- Storm Live: HKO/CMA/JMA/CWA MapLibre path validated on real devices.
- Storm Archive: deferred TODO; not production-ready.

Do not describe Rain/Radar/Forecast/Storm as a fully completed integration until
the Forecast work is explicitly closed.

## 3. Renderer ownership

### Radar

MapLibre is the production renderer.

### 2-hour Forecast

**MapLibre is the production-visible renderer.**

The Canvas renderer remains in the repository only as a hidden reference/safety
implementation. It must not be exposed by normal Tools navigation or presented
as the production Forecast surface.

Forecast semantics remain:

- future valid-time SWIRLS data;
- preferred 16-frame timeline;
- 6-minute cadence;
- each grid value represents a 30-minute rainfall accumulation;
- run rollover guarded;
- stale-aware foreground/re-entry refresh;
- lazy/bounded frame loading;
- playback/network work stops when the tool is not foreground-active;
- MapLibre raster interpolation remains LINEAR.

Forecast remains an active refinement area. Renderer visibility is settled, but
product closure is not.

### Storm Live

MapLibre is the production renderer. Agency identity is preserved; track lines
use agency identity while nodes use Storm-Track intensity colours.

## 4. Host state independence

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

`TOOLS` and `SETTINGS` remain usable even while normal weather is loading or has
failed. Normal weather loading/error UI belongs only to `CURRENT` and
`FORECAST`.

## 5. Location ownership

Weather Metro owns the only Android location pipeline:

```text
LocationRepository
  → WeatherRepository
  → WeatherSnapshot.location
  → RainHostViewModel.bindHostLocation(...)
  → RainRadarHostViewModel.bindHostLocation(...)
  → StormHostViewModel.bindHostLocation(...)
```

Tool modules must not create another fused-location owner or independently
request location permission.

## 6. Backend boundaries

### Rain / Radar

Production origin:

```text
https://radar.max-yu.workers.dev
```

Weather Metro consumes documented runtime APIs for point rainfall, SWIRLS
Forecast, nowcast fallback, Radar contracts/timelines and Radar image routes.
No arbitrary image/proxy URL builder is exposed to Compose.

### Storm

Production origin:

```text
https://storm.max-yu.workers.dev
```

Weather Metro consumes documented public runtime APIs for HKO/CMA/JMA/CWA Live
transport and the existing normalized History APIs.

History APIs are only a foundation for future Archive work; their presence does
not mean the Archive product is production-ready.

The Android app must never contain Cloudflare admin credentials, D1/R2
credentials, CWA authorization secrets, or unrestricted Worker proxy
construction.

## 7. Lifecycle contract

ToolsHome is not fullscreen. Point rainfall, Radar, Forecast and Storm are
fullscreen internal destinations.

While a fullscreen tool is active:

- host Pivot chrome is hidden;
- Pivot swipe navigation is disabled;
- Android Back returns to ToolsHome;
- the active tool owns back/refresh/status/contextual controls;
- leaving the tool cancels disposable requests and stops playback/animation;
- hidden/background tools do not poll;
- effective activity requires both selected Tools destination and Android
  lifecycle `RESUMED`;
- state/cache needed for quick return may remain in the ViewModel.

## 8. Refresh/cache contract

- Point and Forecast successful data is treated as fresh for 15 minutes.
- Point/Forecast stale or failed refreshes use a 5-minute retry backoff.
- Forecast frame prefetch is bounded to nearby frames rather than the whole
  16-frame timeline.
- Radar LIVE uses its independent freshness/auto-refresh policy.
- Storm Live agencies keep independent last-success snapshots and selective
  refresh/backoff state.
- Failed refresh never deletes good last-known data.

Radar preferences remain settings, not disposable cache.

## 9. Storm Archive status

Storm Archive is explicitly **deferred**.

Reasons:

1. the standalone Storm-Track project is still accumulating historical storm
   and advisory records;
2. Archive behavior has not yet completed formal functional and real-device
   validation in the standalone product;
3. Weather Metro should not freeze a native Archive UX/contract before the
   source history is mature enough to test properly.

When resumed, Archive must reuse the normalized History APIs already present in
the Android data layer. It must not reintroduce Leaflet, WebView, a second app
shell, or unrestricted Worker access.

## 10. Security invariants

- HTTPS only.
- No WebView or JavaScript bridge for native Tools.
- No Leaflet runtime inside Weather Metro.
- Production origins live in `ToolEndpoints`.
- Rain and Storm Workers remain separate production services.
- HKO/CMA/JMA/CWA remain independently identified sources.
- An external-agency forecast must never be presented as HKO output.

## 11. Current priority

Current priority is **Forecast MapLibre refinement and final production
validation**, not Storm Archive.

See `docs/TOOLS_TODO.md` for the active queue.

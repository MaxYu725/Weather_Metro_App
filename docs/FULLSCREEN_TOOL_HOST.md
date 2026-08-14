# Weather Metro — Fullscreen tool-host contract

Status: **IR1 integrated native Tools review**

## Product rule

The top-level Weather Metro Pivot is:

```text
current / forecast / tools / settings
```

`tools` is the discovery/home surface only. Once the user enters a native tool capability, the tool owns the full available app surface and the top Pivot chrome is hidden.

Current fullscreen destinations:

```text
ToolsHome
  ├── Point rainfall ── fullscreen
  ├── Radar ─────────── fullscreen
  ├── 2-hour Forecast ─ fullscreen
  ├── Forecast MapLibre reference ─ fullscreen
  └── Storm Live ────── fullscreen
```

While a fullscreen tool is active:

- the host Pivot header is not rendered;
- main Pivot swipe navigation is disabled;
- Android Back returns to `ToolsHome`;
- the tool provides its own back, refresh, status and contextual controls;
- map/timeline/popup/sheet layout is owned by the tool rather than embedded in the normal Pivot page column;
- disposable requests, playback and animation are cancelled or paused when the tool is hidden;
- background/hidden tools do not poll.

Returning to `ToolsHome` restores the normal Pivot header and swipe navigation.

## Host load-state ownership

Fullscreen presentation does not make tool data a child of normal weather load state.

Only `CURRENT` and `FORECAST` depend on the normal HKO/Open-Meteo `WeatherLoadState`. `TOOLS` and `SETTINGS` must remain usable while normal weather is loading or has failed.

Tool state owners remain independent:

```text
RainHostViewModel       → point rainfall / SWIRLS Forecast
RainRadarHostViewModel  → observed Radar
StormHostViewModel      → HKO/CMA/JMA/CWA Live
```

Normal weather refresh progress must not be presented as if a Tools/Settings refresh were running.

## Rain composition

Rain keeps modular entry points even though the map presentation follows the standalone Rain-Track design language.

The fullscreen Forecast composition is conceptually:

```text
Rain fullscreen surface
  ├── native basemap + forecast overlay
  ├── top Rain HUD
  │    ├── location / point-rain summary
  │    ├── back
  │    ├── refresh
  │    └── contextual controls
  ├── forecast data-health state
  ├── map controls
  └── floating 6-minute forecast timeline
```

The Rain visual shell does not change service semantics:

- SWIRLS remains 16 future valid times at 6-minute cadence;
- each grid value remains a 30-minute accumulated rainfall amount;
- Radar remains observed/past data rather than forecast;
- Radar and Forecast settings/clocks remain independent;
- point rainfall, Radar and Forecast remain reusable capabilities rather than a mandatory nested Rain app shell.

Canvas remains the Forecast safety/reference renderer while MapLibre Forecast remains a validated experimental/reference path until a dedicated renderer-consolidation checkpoint.

## Radar composition

Radar uses the same fullscreen ownership model and MapLibre-native raster rendering.

Production behavior includes:

- Worker-owned Radar product contract;
- LIVE/TEST modes;
- playback/timeline/opacity/product controls;
- foreground-only LIVE auto refresh;
- freshness indication;
- last-good retention and bounded prefetch;
- cancellation when hidden/backgrounded.

Radar does not create a second host location owner.

## Storm Live composition

Storm Live is now a completed native fullscreen tool rather than a future integration target.

```text
Storm Live fullscreen surface
  ├── MapLibre basemap
  ├── HKO / CMA / JMA / CWA source controls
  ├── analysis + forecast paths
  ├── intensity-coloured track points
  ├── probability / wind geometry where supplied
  ├── point-anchored detail popup
  ├── refresh
  └── full-view camera control
```

Storm Live rules:

- agencies remain independent official sources;
- one source failure does not remove another source;
- last-success cache is source-scoped;
- manual update refreshes all agencies;
- foreground policy refreshes only stale/failed agencies;
- hidden/background Storm does not poll;
- source visibility toggles do not trigger downloads;
- a selected point is resolved against the current ViewModel snapshot and closes if invalidated.

## Future Storm Archive

Archive will reuse this same fullscreen host contract. It must not introduce another app-inside-app shell, WebView, Leaflet runtime or permanent top-level Pivot page.

Expected internal navigation:

```text
ToolsHome
  └── Storm
       ├── Live
       └── Archive
            ├── storm list
            ├── advisory timeline
            └── advisory playback/detail
```

Archive replay/timers and disposable requests must stop when hidden. Backend storm/advisory IDs remain authoritative.

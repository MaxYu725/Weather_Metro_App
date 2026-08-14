# Weather Metro — Fullscreen tool-host contract

Status: Phase 3B2

## Product rule

The top-level Weather Metro Pivot remains:

```text
current / hourly / forecast / tools / settings
```

`tools` is the discovery/home surface only. Once the user enters a native tool capability, the tool owns the full available app surface and the top Pivot chrome is hidden.

This applies consistently to Rain and future Storm integration:

```text
ToolsHome
  ├── Point rainfall ── fullscreen
  ├── Radar ─────────── fullscreen
  ├── 2-hour Forecast ─ fullscreen
  └── Storm ─────────── fullscreen
```

While a fullscreen tool is active:

- the `tools / settings` Pivot header is not rendered;
- main Pivot swipe navigation is disabled;
- Android Back returns to `ToolsHome`;
- the tool provides its own back, refresh, status and contextual controls;
- map/timeline/sheet layout is owned by the tool rather than being embedded in the normal Pivot page column;
- tool requests/animation are cancelled or paused when leaving the tool according to that module's lifecycle contract.

Returning to `ToolsHome` restores the normal Pivot header and swipe navigation.

## Rain composition

Rain keeps modular entry points even though the map presentation visually follows the standalone Rain-Track design language.

The fullscreen forecast composition is:

```text
Rain fullscreen surface
  ├── native basemap + forecast overlay
  ├── top Rain HUD
  │    ├── location / point-rain summary
  │    ├── back
  │    ├── refresh
  │    └── contextual details control
  ├── forecast data-health chip
  ├── map zoom controls
  ├── floating 6-minute forecast timeline HUD
  └── point-rain bottom sheet
```

The timeline is positioned relative to the visible bottom-sheet height. It must not overlap the sheet in either compact or expanded state.

The Rain visual shell does not change service semantics:

- SWIRLS remains 16 forecast valid times at 6-minute cadence;
- every grid value remains a 30-minute accumulated rainfall amount;
- Radar remains observation rather than forecast;
- point rainfall, Radar, Forecast and settings remain reusable capabilities rather than a mandatory `RainTrackScreen` navigation layer.

## Storm reuse

Storm must not introduce a second app-inside-app navigation shell or keep the Weather Metro Pivot visible inside Live/Archive map experiences.

Future Storm surfaces enter through the same fullscreen host state. Storm owns its own internal Live/Archive controls, agency selection, replay timeline, detail sheets and status HUD while the main Weather Metro Pivot is hidden.

The fullscreen host controls presentation only. Rain and Storm keep independent repositories, caches and load state.
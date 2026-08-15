# Native Tools TODO

This file tracks work that is intentionally **not yet closed**.

## P0 — 2-hour Forecast MapLibre — COMPLETE

MapLibre is the production-visible Forecast renderer. Canvas is retained only as
a hidden reference/safety implementation.

Completed Forecast refinement:

- MapLibre-only production path and real-device renderer validation;
- persistent playback-speed and rainfall-opacity settings plus recenter control;
- 16-slot lazy/bounded playback with failed-frame skip/manual retry behavior;
- last-good raster retention while a requested frame is loading or fails;
- SWIRLS run rollover alignment by forecast lead time;
- 15-minute stale-aware re-entry and 5-minute failed-refresh backoff;
- run-scoped bitmap reuse with a 2 MiB rendering-memory budget;
- background/inactive bitmap-cache release and async lifecycle race hardening;
- repeated 16-frame playback, pan/zoom and background/resume real-device tests;
- real-rain MapLibre raster validation with LINEAR resampling;
- F4 selected-frame timeline following on real devices.

Forecast P0 is closed. Overall Tools integration remains open until P1 passes.

## P1 — Cross-tool final integration review — IN PROGRESS

Review scope:

- Point rainfall regression;
- Radar regression;
- Forecast regression;
- Storm Live regression;
- fullscreen/back/navigation regression;
- foreground/background lifecycle regression;
- cache clearing and stale-data regression;
- production-only surface cleanup;
- final documentation/status cleanup.

P1R1 production cleanup findings:

- production Radar must never reopen in persisted TEST mode;
- TEST transport/test fixtures may remain internally, but production UI exposes
  LIVE only;
- Settings cache clearing must also release Radar transient prefetched image
  bytes while preserving user preferences such as range, opacity and playback
  speed.

Do not close overall Tools integration until the remaining P1 regression review
has passed on the merged production baseline.

## Deferred — Storm Archive

Storm Archive remains a future task, not the next production milestone.

Current reasons for deferral:

- the standalone `MaxYu725/Storm-Track` project is still accumulating historical
  storm/advisory records;
- Archive behavior has not yet completed formal standalone functional testing;
- there is not yet enough confidence to freeze the Weather Metro native Archive
  interaction model.

When the source archive is mature enough to test, resume with:

1. validate standalone history list/detail/advisory playback using accumulated
   real records;
2. confirm History API stability and missing/partial-record behavior;
3. define native archive list/detail/playback UX;
4. reuse the existing normalized Android History data layer;
5. implement MapLibre historical track playback without Leaflet/WebView;
6. add cache/lifecycle/error-isolation tests;
7. complete real-device validation before production exposure.

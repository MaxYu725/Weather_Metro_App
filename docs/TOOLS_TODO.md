# Native Tools TODO

This file tracks work that is intentionally **not yet closed**.

## P0 — 2-hour Forecast MapLibre refinement

MapLibre is the production-visible Forecast renderer. Canvas is retained only as
a hidden reference/safety implementation.

Remaining Forecast work:

- complete another real-device pass using MapLibre only;
- verify repeated 16-slot playback with lazy/bounded frame loading;
- review map/HUD spacing and controls on phone-sized screens;
- verify stale-aware re-entry after longer idle periods;
- verify frame/run rollover behavior when a new SWIRLS run appears while the
  Forecast tool is open;
- test slow/failed individual frame loads without blanking the last-good frame;
- review memory use during repeated playback and map interaction;
- verify background/resume behavior with MapLibre map lifecycle;
- test real rain cases before declaring Forecast production-complete.

Do not close overall Tools integration until Forecast has passed this work.

## P1 — Cross-tool final integration review

After Forecast is complete:

- Point rainfall regression;
- Radar regression;
- Forecast regression;
- Storm Live regression;
- fullscreen/back/navigation regression;
- foreground/background lifecycle regression;
- cache clearing and stale-data regression;
- final documentation/status cleanup.

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

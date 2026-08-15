# Native Tools TODO

This file records completed native Tools milestones and work that remains
intentionally deferred.

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

Forecast P0 is closed.

## P1 — Cross-tool final integration review — COMPLETE

P1 code/static closure review and the final continuous real-device smoke both
passed. The current production native Tools integration is therefore closed for:

- Point rainfall;
- Radar;
- 2-hour Forecast;
- Storm Live.

Merged P1 baseline used for the final real-device smoke:

`main@981a0d0d95c020bd59a8f7658bc4f7b25cc3bc1d`

### P1R1 — production Radar cleanup — COMPLETE

- production Radar always starts in LIVE mode;
- persisted legacy TEST mode is normalized back to LIVE;
- TEST transport/fixtures remain internal but production UI exposes LIVE only;
- Settings cache clearing also releases Radar transient prefetched image bytes
  without resetting range, opacity or playback-speed preferences.

### P1R2 — host-location independence — COMPLETE

- Weather Metro still owns exactly one `LocationRepository` / fused-location
  pipeline;
- location is resolved once before normal weather transport;
- the resolved `LocationInfo` is exposed to Point/Radar/Storm independently of
  `WeatherLoadState`;
- the same resolved location is passed to normal Weather refresh, so no second
  location lookup or second permission owner is introduced;
- a first-load HKO Weather failure with no cached Weather snapshot no longer
  prevents native Tools from receiving a host location.

### P1R3 — Rain/Radar production error isolation — COMPLETE

- Rain, Radar and Forecast transport/parser/schema failures are normalized to
  stable user-facing messages at the repository boundary;
- raw HTTP/JSON/SWIRLS/internal exception text is not exposed by normal Tools UI;
- last-good/stale behavior is unchanged;
- `CancellationException` and Forecast run-rollover control flow remain intact;
- parser-validation patterns such as missing grid/contract fields and invalid
  frame indexes are covered by regression tests.

### P1R4 — zero-base closure scan — COMPLETE

Static production review found no further runtime blocker in:

- Point/Radar/Forecast/Storm navigation and Android Back handling;
- fullscreen Pivot lock/unlock behavior;
- effective foreground state (`Tools` selected + Android `RESUMED`);
- leaving/background cancellation and playback stop;
- MapLibre lifecycle disposal;
- cache clearing and last-good/stale retention;
- Storm per-agency isolation;
- production Forecast renderer selection;
- production Radar LIVE-only surface.

### Final real-device smoke — PASS

One continuous pass on the merged P1 baseline verified:

1. **ToolsHome / host navigation**
   - all four fullscreen tools open normally;
   - host Pivot chrome hides while inside a tool;
   - Android Back and tool back controls return to ToolsHome;
   - repeated tool entry/exit does not freeze.

2. **Point rainfall**
   - host location is available;
   - nearby radius change and manual refresh work;
   - background/resume does not leave permanent loading.

3. **Radar**
   - production opens in LIVE and no TEST control is exposed;
   - range/height, timeline, playback and refresh work;
   - background/resume and Back remain stable.

4. **2-hour Forecast**
   - MapLibre is the only production-visible renderer;
   - playback traverses the timeline and selected-frame following works;
   - manual timeline browsing, pan/zoom/recenter and background/resume remain
     stable.

5. **Storm Live**
   - HKO/CMA/JMA/CWA source chips remain independently usable;
   - paths/points and point-detail interaction work;
   - background/resume and Back remain stable.

6. **Settings cache clear**
   - native Tools reload cleanly after cache clear;
   - Radar preferences remain while transient image cache is discarded.

No new runtime blocker was found. P1 is closed. Do not reopen validated P0/P1
behavior for cosmetic or speculative refactoring.

## Deferred — Storm Archive

Storm Archive remains a future task and is not part of the completed P1 scope.

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

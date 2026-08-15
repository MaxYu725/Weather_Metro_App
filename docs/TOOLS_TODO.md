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

Forecast P0 is closed.

## P1 — Cross-tool final integration review — FINAL DEVICE SMOKE

P1 code/static closure review is complete. Runtime baseline before this docs-only
closure batch:

`main@18a70a465461618bb682ca6afe2cfd0cea72cc6c`

Completed P1 batches:

### P1R1 — production Radar cleanup

- production Radar always starts in LIVE mode;
- persisted legacy TEST mode is normalized back to LIVE;
- TEST transport/fixtures remain internal but production UI exposes LIVE only;
- Settings cache clearing also releases Radar transient prefetched image bytes
  without resetting range, opacity or playback-speed preferences.

### P1R2 — host-location independence

- Weather Metro still owns exactly one `LocationRepository` / fused-location
  pipeline;
- location is resolved once before normal weather transport;
- the resolved `LocationInfo` is exposed to Point/Radar/Storm independently of
  `WeatherLoadState`;
- the same resolved location is passed to normal Weather refresh, so no second
  location lookup or second permission owner is introduced;
- a first-load HKO Weather failure with no cached Weather snapshot no longer
  prevents native Tools from receiving a host location.

### P1R3 — Rain/Radar production error isolation

- Rain, Radar and Forecast transport/parser/schema failures are normalized to
  stable user-facing messages at the repository boundary;
- raw HTTP/JSON/SWIRLS/internal exception text is not exposed by normal Tools UI;
- last-good/stale behavior is unchanged;
- `CancellationException` and Forecast run-rollover control flow remain intact;
- parser-validation patterns such as missing grid/contract fields and invalid
  frame indexes are covered by regression tests.

### P1R4 — zero-base closure scan

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

The remaining P1 gate is one final real-device smoke on the merged P1 runtime.
It should be run as one continuous pass rather than repeating deep feature
validation already completed in earlier phases.

### Final real-device smoke matrix

1. **ToolsHome / host navigation**
   - open ToolsHome;
   - enter each fullscreen tool and confirm host Pivot chrome disappears;
   - Android Back and each tool's own back control return to ToolsHome;
   - no freeze when repeatedly entering/leaving tools.

2. **Point rainfall**
   - correct current/default host location label appears;
   - change nearby radius and refresh once;
   - data or clean stale/error state remains readable;
   - background/resume does not leave a permanent loading state.

3. **Radar**
   - opens in LIVE; no TEST chip/control is visible;
   - range/height selection, timeline selection and playback work;
   - refresh preserves a usable last-good timeline if the update is unavailable;
   - background/resume and Back do not freeze or keep animation running.

4. **2-hour Forecast**
   - MapLibre is the only production-visible renderer;
   - playback traverses the timeline and selected-frame following remains correct;
   - manual horizontal timeline browsing still works when selection is unchanged;
   - pan/zoom/recenter and background/resume remain stable.

5. **Storm Live**
   - HKO/CMA/JMA/CWA source chips remain independently usable;
   - paths/points render correctly and point details open/close;
   - refresh failure of one source does not remove other successful sources;
   - background/resume and Back remain stable.

6. **Settings cache clear**
   - clear cache once from Settings;
   - return to Tools and confirm modules can reload cleanly;
   - Radar user preferences remain while transient image cache is discarded.

### P1 closure condition

If the final smoke passes without a new blocker, mark P1 and the current native
Tools integration **COMPLETE**. Do not reopen already validated feature phases
for cosmetic or speculative refactoring.

## Deferred — Storm Archive

Storm Archive remains a future task and is not part of P1 closure.

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

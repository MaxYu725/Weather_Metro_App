# Notification 2D2F — real-device activation diagnostics

## Purpose

Phase 2D2F adds a local, privacy-safe diagnostics surface for the real-device activation and upgrade gate of personalised rain notifications.

It does not add a new notification stream, network endpoint, periodic worker, location owner, or backend location upload.

## Why this exists

Phase 2D2E intentionally reuses the existing 2D1 WorkManager unique work names. The real-device gate therefore needs to verify upgrade behaviour on the installed WorkManager database rather than infer correctness only from source code.

The Settings diagnostics tile reads the installed device state directly and reports whether the shared local-notification cadence is healthy.

## WorkManager checks

Diagnostics queries the existing unique work names:

- `weather-location-heavy-rain-periodic`
- `weather-location-heavy-rain-now`

Only active WorkInfo states are counted: ENQUEUED, RUNNING and BLOCKED. Completed, failed and cancelled historical rows do not count as active duplicates.

When local notification scheduling is expected, the periodic gate requires:

1. exactly one active periodic WorkInfo;
2. `dispatch_location_heavy_rain = true`;
3. `dispatch_personalized_rain = true`;
4. a cached host location inside the six-hour freshness policy.

Verdicts:

- `READY` — exactly one active shared periodic cadence and valid dispatch inputs;
- `DISABLED` — cadence is not expected and no active periodic work remains;
- `LOCATION_UNAVAILABLE` — scheduling is expected but no host location snapshot exists;
- `LOCATION_STALE` — cached host location exceeds the six-hour policy;
- `PERIODIC_MISSING` — scheduling is expected but there is no active periodic WorkInfo;
- `PERIODIC_DUPLICATE` — more than one active periodic WorkInfo exists;
- `PERIODIC_DISPATCH_INVALID` — the installed periodic WorkSpec does not contain both required dispatch flags;
- `STOPPING_OR_STALE_WORK` — scheduling is disabled but an active periodic WorkInfo is still visible;
- `READ_ERROR` — local diagnostics could not be read safely.

`STOPPING_OR_STALE_WORK` may be transient immediately after a cancellation request. A manual refresh lets the tester distinguish a normal asynchronous WorkManager cancellation from persistent stale work.

## Stream state shown

The diagnostics tile also shows:

- active immediate-work count and its current dispatch flags;
- cached district and location age;
- 2D1 durable state status and last-check age;
- SWIRLS durable state status and last-check age;
- last SWIRLS source-run age;
- pending SWIRLS event kind, when one exists.

This makes a real-device opt-in run observable without adb.

## Privacy boundary

The diagnostics model deliberately does **not** expose latitude or longitude.

The exact location remains inside `PersonalizedNotificationLocationStore` and the existing local grid-sampling path. Diagnostics receives only:

- district;
- age/freshness;
- local durable notification state;
- WorkManager metadata.

No diagnostics information is uploaded.

## UI behaviour

Opening the Settings pivot refreshes diagnostics once. Tapping the `notification diagnostics` tile refreshes it again.

The query runs from `WeatherViewModel` on `Dispatchers.IO`, so WorkManager database inspection does not block the Compose UI thread.

## Automated coverage

`PersonalizedNotificationDiagnosticsTest` covers the verdict state machine:

- clean disabled state;
- disabled state with stale active work;
- unavailable/stale location;
- missing periodic work;
- duplicate periodic work;
- invalid dispatch flags;
- healthy ready state.

This complements the 2D2E activation/dispatch tests; it does not replace real-device verification of the installed WorkManager database.

## Real-device acceptance gate

Before PR #66 leaves Draft, verify on the target Android device:

1. upgrade from the current production build;
2. open Settings and confirm `rain approaching` is still off;
3. with only 2D1 enabled, refresh diagnostics and confirm one shared periodic work exists;
4. enable `rain approaching` and confirm an immediate SWIRLS evaluation is dispatched;
5. refresh diagnostics after the immediate run and confirm `READY` with `periodic 1 active`;
6. turn 2D1 off while SWIRLS remains on and confirm the shared periodic work remains;
7. turn SWIRLS off while 2D1 remains on and confirm the shared periodic work remains;
8. turn both local streams off, or disable precise location / global notifications, and confirm the cadence becomes `DISABLED` after cancellation settles;
9. confirm the SWIRLS status/source-run fields advance when the opt-in worker executes;
10. independently inspect network traffic if required and confirm there is no request to the Rain-Track point endpoint carrying precise lat/lon.

## 2026-08-16 first real-device matrix

User-provided Settings diagnostics screenshots verify the following installed-device behaviour:

- **2D1 ON / SWIRLS OFF — PASS.** `rain approaching` remained off, one shared periodic work was active, and the visible immediate request was selective to 2D1 (`2D1 on / SWIRLS off`).
- **2D1 OFF / SWIRLS ON — cadence PASS, SWIRLS execution pending.** One shared periodic work remained active after disabling 2D1, proving the cadence is not owned by the 2D1 feature toggle. The captured immediate work still showed an in-flight request and SWIRLS durable state was still `IDLE / checked never / source never`, so a later refresh is still required to prove completion of the first SWIRLS evaluation.
- **global weather notifications OFF — PASS.** Diagnostics settled to `DISABLED` with periodic 0 active and immediate 0 active while the cached local location remained available.
- **precise location OFF with global notifications ON — PASS.** Diagnostics settled to `DISABLED` with periodic 0 active and immediate 0 active; the personalised location store was cleared and displayed as unavailable.

The periodic diagnostic tags describe what the WorkRequest requested; the worker still re-checks the live per-stream setting and does not trust diagnostic tags for execution authorization. A transient immediate request may therefore still display a previously requested stream while that stream has just been disabled. The durable state / notification result after the request settles is the acceptance signal.

Remaining real-device closure:

- leave 2D1 off and `rain approaching` on;
- allow the immediate work to finish, then refresh diagnostics;
- confirm SWIRLS is no longer `checked never` and the source-run field is no longer `never` (or shows an explicit fail-closed source status/error rather than silently remaining IDLE);
- confirm no 2D1 notification/state is produced while its setting is off.

Keep PR #66 Draft until these remaining real-device checks are complete.

# Notification Phase 2D2C — SWIRLS background economics + durable rain state

## Scope

This checkpoint prepares the Phase 2D2 personalised rain stream for later background execution without enabling a new Worker yet.

It adds two pieces:

1. an adaptive SWIRLS background fetch planner; and
2. a durable local episode/pending-transition state store.

No new WorkManager schedule, Android notification publication, UI, lightning runtime, or territory-wide warning-journal path is enabled here.

## Why a separate background fetch policy is needed

The integrated SWIRLS contract contains:

- 16 frames;
- 6-minute cadence;
- 30-minute overlapping accumulation windows;
- 121 x 121 grid cells per frame;
- 14,641 rainfall values per frame;
- 234,256 rainfall cell values for all 16 frames in one run.

The current Rain Worker exposes timeline metadata together with a full frame response. Weather Metro therefore still needs frame 0 as the discovery request for a run.

Blindly loading every remaining full grid every time a background Worker runs would be wasteful during the much more common dry case. Conversely, permanently reducing the stream to sparse frames would throw away the 6-minute temporal resolution precisely when rain is approaching.

2D2C therefore uses an adaptive two-stage plan.

## Stage 1 — dry/background baseline

After frame 0 establishes the current SWIRLS run and timeline, select future frames relative to **device now**, not merely model lead time:

- `0..30 minutes`: every available frame — full 6-minute density;
- `31..60 minutes`: every second frame — about 12-minute scouting;
- `61..120 minutes`: every third frame — about 18-minute scouting;
- include each band end / final available horizon frame.

For a completely fresh run evaluated at run time, the selected indices are:

`0, 1, 3, 5, 6, 9, 12, 15`

That is 8 full grids rather than 16:

- baseline: 8 x 14,641 = **117,128 cell values**;
- full run: 16 x 14,641 = **234,256 cell values**.

This is a structural comparison of decoded grid values. It deliberately does **not** claim a fixed byte saving because JSON number lengths, HTTP content encoding, Worker headers and upstream MDL text sizes vary by run.

If frame 0 is already expired when the device evaluates the run, expired frames are not selected again. The planner always reasons from each frame's actual `validTime`.

## Stage 2 — dense completion

Sparse scouting is allowed only while there is no known rain episode.

Switch to full future 6-minute coverage when either condition is true:

- the durable rain episode is already active; or
- any baseline/scout local sample is at least the existing Rain-Track wet threshold.

The remaining un-fetched future frames are then loaded and the normal 2D2B evaluator runs on the dense local profile.

This means the optimization is primarily a **dry-weather network optimization**. It does not intentionally lower temporal resolution during an actual rain episode.

A narrow far-horizon rain pulse could theoretically fall between sparse scout frames on one background pass. The intended later Worker cadence is therefore part of the reliability model: as time advances, that horizon moves into the denser 30-minute band before arrival. 2D2C does not yet activate that schedule and makes no delivery-time guarantee.

## Privacy boundary remains unchanged

The adaptive planner downloads location-independent SWIRLS grids.

The device then combines those grids with `PersonalizedNotificationLocationStore`, which contains the last precise location already resolved by the Weather Metro host location owner.

The personalised notification path does not call the Rain-Track point endpoint and does not send precise device latitude/longitude to Rain-Track for evaluation.

## No SWIRLS full-grid disk cache in this checkpoint

`RainCache` currently persists capabilities, point responses and nowcast fallback payloads. SWIRLS lazy frames are held in `RainRepository` memory only.

2D2C deliberately does not add a disk cache containing sixteen full SWIRLS JSON grids. For personalised notifications, the long-lived value is the tiny local episode state, not the entire weather field.

A later runtime checkpoint can choose whether to retain a compact sampled profile for the active run. That should be measured against actual Worker cadence and process-lifetime behaviour before adding another cache owner.

## Durable episode state

`PersonalizedRainEpisodeStateStore` persists only local personalised state in its own SharedPreferences namespace.

It stores:

- the committed evaluator episode state;
- an optional pending notification transition;
- the latest source-run timestamp;
- the latest evaluation timestamp;
- diagnostic status/error text.

It is separate from:

- the territory-wide HKO publication journal;
- the existing location-heavy-rain district state;
- Rain forecast/cache storage.

## Pending-transition reliability ordering

An evaluator decision that contains **no notification** can commit its next episode state immediately.

An evaluator decision that contains a notification must follow this order in the later Worker:

1. evaluate a transition;
2. synchronously persist `pendingTransition` while leaving the previous episode state committed;
3. insert/replay the deterministic event in `NotificationEventStore`;
4. only after the local inbox accepts the event, commit `targetEpisodeState` and clear the pending transition.

If the process dies between steps 2 and 4, the pending transition survives and must be replayed before any fresh evaluation is allowed to overwrite it.

This extends the reliability pattern already used by the location-heavy-rain stream without mixing personalised events into the global HKO journal.

## Stored pending identity

A pending transition contains:

- `PersonalizedForecastEventIdentity`;
- horizon;
- detection timestamp;
- SWIRLS run timestamp;
- target evaluator episode state.

The deterministic identity remains the dedupe owner. A later publisher should derive its local inbox `eventId` from that identity rather than inventing a second dedupe scheme.

## Fail-closed state decoding

Malformed persisted JSON is ignored and replaced with a `STATE_RESET` empty state. Unknown enum values or invalid required pending timestamps also fail closed rather than continuing an ambiguous episode.

## Tests added

Pure JVM tests cover:

- fresh-run baseline indices;
- 50% decoded-grid selection at the run boundary;
- expired-frame removal using device-now;
- active-episode dense completion;
- wet-scout promotion to dense completion;
- pending-before-commit ordering;
- non-notification immediate state progress;
- protection against overwriting an existing pending transition;
- durable codec round-trip;
- invalid-state fail-closed reset.

## Next checkpoint

The next runtime checkpoint may connect these pieces into a dedicated personalised-rain Worker:

`cached host location -> SWIRLS discovery -> adaptive baseline -> local sampling -> optional dense completion -> evaluator -> durable pending transition -> NotificationEventStore -> Android publisher`

Before enabling it, scheduling and source-fetch cadence must be reviewed against Android WorkManager's minimum periodic interval and existing immediate foreground-trigger paths. The territory-wide one-minute warning monitor must remain completely unaffected.

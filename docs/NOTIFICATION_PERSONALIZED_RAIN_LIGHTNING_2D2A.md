# Notification Phase 2D2A — Personalised rain / lightning contract

## Scope

This checkpoint is deliberately limited to **official-source research and a local Android event contract**. It does not add a new background worker, production lightning client, notification UI, or any entry to the territory-wide HKO warning journal.

The personalised stream remains separate from official territory-wide publications:

```text
official public weather data
        ↓
Android local repository / parser
        ↓
Weather Metro cached host location
        ↓
local evaluator + durable episode state
        ↓
local notification inbox / publisher
```

A personalised event is derived from official public data but is **not itself an HKO warning publication**.

## Existing owner review

### Location

`LocationRepository` remains Weather Metro's only requester of Android location. The notification subsystem must not create another `FusedLocationProviderClient`, request background location, or independently resolve a new location.

`PersonalizedNotificationLocationStore` already provides the required hand-off for background work: it stores the latest precise host fix locally together with district, accuracy and update time. The current 2D1 worker already fails closed once that cached fix is more than six hours old.

Phase 2D2 reuses this owner boundary unchanged.

### Rain

Weather Metro already owns:

- `RainTrackClient`;
- `RainRepository`;
- `RainCache`;
- SWIRLS 16-frame forecast loading;
- 6-minute SWIRLS cadence;
- 30-minute accumulation windows;
- a 121 x 121 location-independent forecast grid;
- a native fixed-location rain UI hosted from the Weather Metro location owner.

There is an important privacy distinction between the two existing rain paths:

1. `RainTrackClient.loadPointForecast(latitude, longitude, radiusKm)` includes the requested latitude and longitude in the Rain-Track request. It is therefore **not the default Phase 2D2 notification source**.
2. `RainForecastClient.loadSwirlsFrame(frameIndex)` fetches a forecast grid without sending a user location. Phase 2D2 can sample this grid on-device using the already-cached host location.

The second path is the required default for personalised notification evaluation.

### WorkManager and notification delivery

2D1 already provides the durability pattern to reuse later:

- WorkManager owns deferred network work;
- transition state is persisted before notification insertion;
- deterministic local event identity makes retries idempotent;
- `NotificationEventStore` / `WeatherNotificationPublisher` remain the local delivery owners;
- derived local notifications remain outside the durable global HKO publication journal.

2D2A does not schedule any new work yet.

## Official lightning source research

Research date: **2026-08-16**.

### 1. HKO Lightning Location Information Service (LLIS)

Official page:

- https://www.hko.gov.hk/tc/wxinfo/llis/gm_index.htm

The HKO public page confirms that the Lightning Location Network:

- displays cloud-to-ground and cloud-to-cloud locations over the Pearl River Estuary and nearby regions;
- displays the previous 30 minutes of lightning;
- updates lightning records every five minutes;
- normally has a delay of a few minutes for communication and computation;
- gives about 250 m cloud-to-ground location accuracy inside the network when all stations are operating;
- has estimated cloud-to-ground detection efficiency of about 95% above the stated current threshold and cloud-to-cloud efficiency above 50%;
- may miss or falsely detect strokes and publishes provisional data with limited validation.

This proves that sufficiently precise official **displayed** strike locations exist. It does **not** by itself establish a supported machine-readable strike-coordinate API contract for Weather Metro.

### 2. Current HKO / DATA.GOV.HK open-data catalogue

Official catalogue:

- https://www.hko.gov.hk/en/abouthko/opendata_intro.htm
- https://data.gov.hk/en-data/dataset/hk-hko-rss-cloud-ground-lightning-count-past-hour

The currently documented real-time lightning dataset is `LHL`, **Lightning count over Hong Kong territory in the past hour**. It is updated hourly and provides cloud-to-ground counts for four Hong Kong regions plus the territory-wide cloud-to-cloud count.

It does not provide per-strike latitude / longitude coordinates and is therefore insufficient for a local distance-to-device evaluator.

The current HKO open-data catalogue does not document a per-strike coordinate feed that can be treated as a stable production API by this checkpoint.

### 3. HKO one-hour lightning nowcast (beta)

Official notes:

- https://maps.weather.gov.hk/ocf/help_e.html

HKO's SWIRLS lightning nowcast publishes ten forecast maps in six-minute steps. Under normal operation the product is refreshed about every three minutes, while HKO notes that radar scanning, SWIRLS computation, graphics generation and Internet publication normally take about 20 minutes.

For a selected location HKO uses:

- red: forecast lightning within **10 km**;
- amber: forecast lightning within **15 km**;
- solid icons: first 30-minute period;
- hollow icons: second 30-minute period, with lower certainty.

HKO reports probability of detection within 15 km in the first 30-minute period at around 70%, with lower forecast accuracy in the second 30-minute period.

This is useful as a future **forecast signal**, but it is not a substitute for observed strike coordinates and must not be presented as exact strike detection.

### 4. Public-data usage boundary

DATA.GOV.HK permits reuse of data for commercial and non-commercial purposes free of charge subject to its terms and attribution requirements:

- https://data.gov.hk/en/terms-and-conditions

Material taken directly from HKO web pages is governed separately by HKO website conditions:

- https://www.hko.gov.hk/en/readme/readme.htm

Consequently, Phase 2D2A does **not** promote an undocumented internal LLIS web request into a production data API merely because the public map can display strike points. A production lightning source must have a documented/reliable machine feed and compatible usage terms first.

## Source eligibility decision

| Source | Coordinates sent by Weather Metro | Per-strike coordinates | Intended role | 2D2 production status |
|---|---:|---:|---|---|
| Weather Metro SWIRLS rain grid | No | n/a | local 30/60/120 min rain evaluation | **Eligible** |
| Rain-Track point forecast | **Yes** | n/a | interactive fixed-location UI | Not default for notifications |
| HKO LLIS public map | No device location required to view source | Displayed | observed lightning | **Machine feed not yet approved** |
| HKO `LHL` open data | No | No | territory/region lightning counts | Insufficient for distance alerts |
| HKO 1-hour lightning nowcast beta | Can be evaluated locally if machine data is later confirmed | Forecast cells, not observed strikes | secondary forecast signal | Research / later phase |

## Personalised event contract

`PersonalizedForecastNotificationContract.kt` defines the first common vocabulary.

### Rain transitions

- `RAIN_APPROACHING`
- `RAIN_STARTING_SOON`
- `HEAVY_RAIN_APPROACHING`
- `RAIN_INTENSIFYING`
- `RAIN_ENDING`

The contract deliberately models transitions rather than emitting a periodic "it is raining" reminder.

Rain evaluation horizons are grouped as:

- 0–30 minutes;
- 31–60 minutes;
- 61–120 minutes.

Actual evaluation must use each SWIRLS frame's absolute `validTime` against the current clock. The nominal frame `leadMinutes` is relative to the SWIRLS model run, not necessarily relative to the moment the Android worker runs.

### Lightning transitions

- `LIGHTNING_NEARBY`
- `LIGHTNING_CLOSER`

The first proximity contract uses **15 km** and **10 km**, matching the distance bands HKO itself exposes in its one-hour lightning nowcast. A 5 km tier is deliberately not introduced until observed machine-source semantics and real-world notification behaviour are validated.

This contract does not activate lightning notifications until an approved per-strike machine source exists.

## Freshness and fail-closed rules

### Location

- cached precise host location maximum age: **6 hours**;
- background notification work never requests a new location itself;
- absent/stale location means no personalised evaluation.

### Rain

- initial SWIRLS run freshness ceiling: **45 minutes**;
- evaluator must additionally require useful future `validTime` frames;
- stale, malformed or run-mismatched data cannot trigger or clear an episode;
- a rain episode is re-armed only after at least two dry six-minute evaluations spanning at least **12 minutes**.

The numerical rain-intensity thresholds are intentionally deferred to the next evaluator checkpoint so that they can be tested against sampled SWIRLS grid values rather than guessed in this contract-only phase.

### Lightning

The provisional source freshness ceiling is **12 minutes**, derived from the documented five-minute LLIS update interval plus its normal several-minute publication delay. This ceiling becomes operative only when a supported machine feed with source timestamps exists.

A lightning episode:

- enters at the first valid strike within 15 km;
- may escalate once when a valid strike enters 10 km;
- does not repeatedly notify for every strike while the same episode remains active;
- clears only after **30 minutes** without a valid strike inside 15 km.

Missing or stale source data must not clear an active episode.

## Dedupe / durability

Each local transition identity contains:

- source;
- event kind;
- episode id;
- transition ordinal.

The deterministic dedupe key is retained locally for at least **24 hours**. A retry or process restart therefore replays the same transition rather than creating a second notification.

This key is local personalised state. It must never become a global HKO journal cursor or publication id.

## Privacy boundary

Phase 2D2 keeps precise user location on the Android device unless a later technical requirement is explicitly reviewed.

The intended rain path is:

```text
HKO/SWIRLS gridded forecast
        ↓
Rain repository
        ↓
Android local grid sampling
        +
last precise host location
        ↓
local rain transition evaluator
```

The intended observed-lightning path, once a supported coordinate feed is available, is:

```text
official strike coordinates + strike timestamps
        ↓
Android local distance calculation
        +
last precise host location
        ↓
local lightning episode evaluator
```

No precise device location belongs in the Apps Script warning journal, FCM territory-wide wake-up payload, or a shared backend personalised-location database.

## Reliability statement

This personalised stream is best-effort Android local automation. WorkManager may be deferred and the device may be force-stopped, offline, permission-restricted or subject to OEM battery controls.

The strong durable-journal guarantee remains specific to territory-wide HKO publications. Personalised rain/lightning events are derived from source snapshots available when Android can execute; they must not be described as a guarantee that every transition will arrive instantly.

## Next checkpoint

Recommended Phase 2D2B scope:

1. add a pure SWIRLS grid sampler that maps the cached host latitude/longitude to the forecast grid without network location upload;
2. add a pure rain episode evaluator using absolute frame times and tested rain-intensity thresholds;
3. unit-test onset, escalation, ending, stale-source and model-run-change behaviour;
4. continue lightning-source discovery separately, but do not ship a production lightning worker until an approved machine-readable strike-coordinate source is confirmed.

No UI or multi-type background scheduler should be added until the pure evaluator is stable.

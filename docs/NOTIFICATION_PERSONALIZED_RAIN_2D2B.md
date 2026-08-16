# Notification Phase 2D2B — Local SWIRLS rain evaluator

## Scope

This checkpoint implements the pure Android-side evaluation layer for the personalised short-range rain stream defined in Phase 2D2A.

It deliberately does **not** add a WorkManager worker, scheduler, UI control, notification publisher, or lightning production source. The purpose is to prove the privacy-safe rain calculation and episode semantics before background execution is connected.

## Privacy / ownership boundary

Weather Metro remains the only device-location owner.

The evaluator accepts:

1. a location-independent SWIRLS grid already downloaded by Weather Metro; and
2. the cached host location already maintained for personalised notifications.

The bilinear sampler runs on Android. It does not call the Rain-Track point endpoint and does not transmit precise latitude / longitude to Rain-Track, Apps Script, FCM, or any other notification backend.

The resulting events remain local personalised events and must never be inserted into the territory-wide HKO publication journal.

## SWIRLS accumulation semantics

Weather Metro's integrated SWIRLS contract exposes:

- 16 frames;
- 6-minute frame cadence;
- 30-minute rainfall accumulation windows;
- lead times from +30 to +120 minutes;
- unit `mm / 30 min`.

Adjacent frames therefore overlap heavily: a 6-minute step advances a 30-minute accumulation window by only 6 minutes. The evaluator **must not sum consecutive frame values** as though they were independent six-minute rainfall totals.

Instead, every frame is sampled independently at the host location. Transition logic uses:

- the frame's actual `windowStart` / `windowEnd` / `validTime`;
- the sampled 30-minute accumulation amount;
- time from the current Android evaluation instant to the frame's `windowEnd`.

This also avoids assuming that SWIRLS `leadMinutes` is measured from the current device time. If a run is already several minutes old, its usable forward horizon from `now` is correspondingly shorter.

## Local grid sampling

`PersonalizedRainGridSampler` performs bilinear interpolation against the parsed `RainForecastGrid`:

- latitude axis: north to south;
- longitude axis: west to east;
- row-major values;
- exact grid-line / exact-cell positions are supported;
- invalid, non-finite, negative or out-of-grid samples fail closed.

The profile builder also fails closed when:

- SWIRLS run time is absent or older than the Phase 2D2A freshness gate;
- frames from different runs are mixed;
- units are not `mm / 30 min`;
- grid contracts differ between supplied frames;
- frame timestamps cannot be parsed;
- accumulation window end does not equal valid time.

## Rain intensity compatibility thresholds

The evaluator reuses the thresholds already used by Rain-Track's point-forecast presentation so Weather Metro does not create a second conflicting product scale:

| Sampled amount (`mm / 30 min`) | Local level |
| --- | --- |
| `< 0.2` | dry |
| `0.2 – <0.5` | light |
| `0.5 – <2.0` | moderate |
| `2.0 – <10.0` | heavy |
| `>= 10.0` | very-heavy |

These are **Weather Metro / Rain-Track product thresholds**, not HKO warning criteria and not a replacement for territory-wide rainstorm warnings.

They are kept in a dedicated `PersonalizedRainThresholds` contract so later field calibration can change the local notification policy without altering SWIRLS parsing or the global warning pipeline.

## 30 / 60 / 120 minute profiles

`PersonalizedRainProfile.horizonSummary()` provides cumulative local summaries for:

- 30 minutes;
- 60 minutes;
- 120 minutes.

Each summary reports:

- number of usable future frame windows ending inside the horizon;
- maximum sampled `mm / 30 min` value;
- peak local intensity class;
- first wet window-end lead time.

A horizon may contain fewer frames than its nominal duration when the current SWIRLS run is already several minutes old. The evaluator uses only actual future valid times and does not synthesize missing coverage.

## Episode / transition model

The first pure episode state is `PersonalizedRainEpisodeState`.

### New episode

If no wet frame is present in the usable 120-minute horizon:

- remain idle;
- no notification transition.

If the first wet frame ends within 30 minutes:

- emit `RAIN_STARTING_SOON`.

If the first wet frame ends between 31 and 120 minutes:

- emit `RAIN_APPROACHING`.

If a `heavy` or stronger frame is already present within the next 30 minutes:

- emit `HEAVY_RAIN_APPROACHING` instead of a weaker initial transition.

### Existing episode

Once an episode exists, its `episodeId` is retained across subsequent SWIRLS runs. A new model run therefore does not automatically create a second rain episode.

Meaningful transitions are:

- approaching episode moves inside 30 minutes → `RAIN_STARTING_SOON`;
- near-term peak rises to a higher `heavy` or stronger class → `RAIN_INTENSIFYING`;
- a near-term episode becomes dry for the required confirmation period → `RAIN_ENDING`.

A 12-minute transition cooldown prevents rapid foreground refreshes from generating near-identical follow-up events.

## Dry confirmation / forecast withdrawal

The Phase 2D2A re-arm rule is implemented as:

- at least 2 dry evaluations;
- first-to-last dry confirmation span at least 12 minutes.

Two cases are deliberately separated:

1. **Episode already reached near-term rain** — confirmed dry state emits one `RAIN_ENDING`, then resets.
2. **Rain was only forecast in the distance and disappears before reaching the near-term window** — the episode resets silently after dry confirmation. It does not claim that rain "ended" when it may simply have been withdrawn from the forecast.

Missing or stale source data is not passed into the transition evaluator, so it cannot clear an active episode.

## Deterministic local identity

Every emitted transition uses `PersonalizedForecastEventIdentity` with:

- source `HKO_SWIRLS_GRID`;
- retained episode id;
- monotonically advancing transition ordinal inside the local state.

This identity is intended for the later local durable inbox / retry layer. It is not a global journal cursor and is never uploaded as a territory-wide publication.

## Tests in this checkpoint

Unit coverage includes:

- exact privacy-safe bilinear interpolation;
- out-of-grid fail-closed behavior;
- Rain-Track compatibility threshold boundaries;
- stale SWIRLS rejection;
- actual-time 30 / 60 / 120 horizon grouping;
- `RAIN_STARTING_SOON`;
- `RAIN_APPROACHING`;
- `HEAVY_RAIN_APPROACHING`;
- approaching → starting-soon transition;
- meaningful intensity escalation;
- cooldown suppression;
- two-step / 12-minute dry confirmation;
- `RAIN_ENDING`;
- silent reset when a distant forecast is withdrawn.

## Deliberate omissions

This checkpoint does not yet decide background fetch economics for all 16 SWIRLS frames. Before a worker is enabled, Phase 2D2C should measure the real frame payload/cache behavior and choose the minimum-fetch strategy that preserves the 6-minute transition signal without sending device coordinates upstream.

It also does not connect the lightning contract to production. Lightning remains blocked on confirmation of a reliable machine-readable per-strike coordinate source.

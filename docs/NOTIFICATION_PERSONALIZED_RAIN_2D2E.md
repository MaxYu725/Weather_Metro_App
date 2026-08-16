# Notification 2D2E — personalised rain activation

## Scope

Phase 2D2E activates the Android personalised SWIRLS rain runtime without adding a second periodic WorkManager cadence.

The existing 2D1 `LocationHeavyRainScheduler` remains the single cadence owner:

- unique periodic work: `weather-location-heavy-rain-periodic`
- interval: WorkManager 15-minute periodic minimum
- network constraint: connected
- existing 2D1 worker class remains the scheduled host
- precise location continues to be supplied only by Weather Metro's existing location owner

`PersonalizedRainWorker` remains unscheduled. The SWIRLS runtime is invoked from the existing `LocationHeavyRainWorker` execution slot when its dedicated setting is enabled.

## Independent streams inside one worker run

The shared worker can execute two independent local notification streams:

1. 2D1 district observed heavy rain
   - source identity: `HKO_LOCATION_DERIVED`
   - HKO district past-hour rainfall
   - existing 50 / 70 mm transition semantics
   - existing durable state and pending publication ordering

2. 2D2 SWIRLS personalised rain
   - source identity: `HKO_SWIRLS_LOCATION_DERIVED`
   - location-independent SWIRLS grids
   - Android local grid sampling
   - adaptive 8-frame dry baseline with dense completion when required
   - 2D2B/2D2C durable episode and pending transition semantics

The streams do not share episode state, pending events or source-type cleanup. A failure in one stream does not mutate the other stream's durable state. If either enabled stream reports a retryable runtime failure, the shared WorkManager run returns `retry` while deterministic local event identity prevents duplicate notification publication.

## Activation rules

The shared cadence is scheduled only when all of the following are true:

- global weather notifications are enabled;
- precise location is enabled; and
- at least one local rain stream is enabled.

The pure activation predicate is covered by `PersonalizedNotificationActivationTest`.

## Dedicated SWIRLS setting

`UiSettings.personalizedRainNotificationsEnabled` and preference key `notification_personalized_rain` are introduced in 2D2E.

The setting is **false by default**. Existing installs therefore do not begin receiving a new class of derived SWIRLS notification merely by upgrading. The user can opt in from Settings via `rain approaching`.

The setting copy states that:

- the signal is based on HKO SWIRLS;
- Weather Metro derives the local notification on-device;
- the precise location is used only for local grid sampling and is not uploaded.

## Immediate evaluation

When an enabled precise host location is freshly resolved by `WeatherViewModel`, the shared scheduler keeps the periodic request and replaces the existing unique immediate request. This preserves a single immediate-work owner while allowing a newly refreshed location to be evaluated promptly.

Enabling either local rain setting also enqueues one immediate evaluation. Disabling a setting does not unnecessarily run the remaining stream immediately.

## Targeted reset semantics

2D2E splits scheduler reset operations:

- `resetHeavyRain()` clears only 2D1 state and `HKO_LOCATION_DERIVED` pending inbox events;
- `resetPersonalizedRain()` clears only 2D2 SWIRLS state and `HKO_SWIRLS_LOCATION_DERIVED` pending inbox events;
- `resetAll()` cancels shared periodic/immediate work and clears both local streams.

Turning off precise location or global notifications uses `resetAll()`. Turning off only one local rain toggle uses the corresponding targeted reset, so the other stream can keep the shared schedule.

## Startup behaviour

`WeatherMetroApplication` does not create a second periodic request. At startup it evaluates the same activation predicate and retains the existing 2D1 unique work names. This makes app upgrades cadence-compatible with the already-installed WorkManager request.

## Still outside this phase

2D2E does not add:

- a second SWIRLS periodic worker;
- a second location owner;
- a location upload path;
- production observed-lightning delivery;
- an undocumented HKO lightning coordinate scraper;
- any change to the territory-wide HKO warning journal;
- any change to the one-minute territory-wide warning monitor.

## Production verification gate

Keep PR #66 Draft after CI. Before merge, production verification should confirm on a real Android device that:

1. upgrading does not create duplicate periodic WorkManager requests;
2. `rain approaching` remains off until explicitly enabled;
3. enabling it produces one immediate shared work request;
4. disabling 2D1 while 2D2 remains enabled does not stop the shared schedule;
5. disabling 2D2 while 2D1 remains enabled does not stop the shared schedule;
6. disabling precise location or global notifications cancels both local streams;
7. no request path sends precise lat/lon to the Rain-Track point endpoint.

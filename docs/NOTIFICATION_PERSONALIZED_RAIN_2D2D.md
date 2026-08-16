# Notification Phase 2D2D — Personalised Rain Worker Runtime

## Scope

2D2D turns the 2D2A–2D2C contracts into an executable Android-side runtime without activating a new persistent schedule yet.

The runtime path is:

```text
Weather Metro cached precise location
        ↓
SWIRLS frame 0 discovery
        ↓
adaptive 2D2C baseline fetch
        ↓
local bilinear grid sampling
        ↓
wet scout / active episode?
        ├─ no  → evaluate sparse dry/background profile
        └─ yes → dense completion to full 6-minute input
        ↓
2D2B rain transition evaluator
        ↓
2D2C durable pending transition
        ↓
NotificationEventStore
        ↓
WeatherNotificationPublisher
        ↓
commit evaluator state only after durable inbox acceptance
```

## Location ownership and privacy

`PersonalizedRainWorker` does not request location.

It only consumes `PersonalizedNotificationLocationStore`, which is populated by Weather Metro's existing location owner. The worker never calls fused location APIs and never sends latitude/longitude to the Rain-Track point endpoint.

SWIRLS frames remain location-independent. Sampling happens on Android.

The durable rain state now stores a local evaluation-location snapshot so an event staged before process death cannot later be replayed with a different location label.

This snapshot remains on-device.

## Location change rule

A rain episode is reset when either:

- the district changes; or
- the new cached position is more than 1 km from the previous rain evaluation position.

The 1 km boundary is a Weather Metro product rule, not an HKO rule. It prevents normal GPS jitter from resetting an episode while avoiding replay of an old local forecast after meaningful movement.

Before resetting, pending SWIRLS rain inbox items are discarded by the dedicated source type:

```text
HKO_SWIRLS_LOCATION_DERIVED
```

This is intentionally separate from the existing 2D1 source type:

```text
HKO_LOCATION_DERIVED
```

so moving location cannot accidentally delete pending 2D1 district heavy-rain events.

## Runtime ordering

### Existing pending transition

The runtime always handles an existing pending transition before requesting fresh SWIRLS data:

```text
read durable state
→ pending exists
→ rebuild deterministic event from persisted transition + persisted location
→ publisher.accept(event)
→ only after durable inbox acceptance commit targetEpisodeState
→ then begin fresh SWIRLS evaluation
```

If publisher acceptance returns false, the transition stays pending and fresh evaluation is not allowed to replace it.

### New transition

For a newly detected event:

```text
evaluate
→ persist pending transition
→ publisher.accept(event)
→ commit targetEpisodeState
```

This keeps personalised rain outside the global HKO publication journal while retaining crash-safe local ordering.

## Network behaviour

The production frame source uses the existing `RainForecastClient` and validates every lazy-loaded SWIRLS frame against the discovery timeline.

Frame requests are fetched in batches of at most three concurrent requests.

Dry/inactive evaluation follows the 2D2C adaptive baseline. If any future baseline sample is wet, or an episode is already active, the runtime fetches every still-missing future frame before final event evaluation.

There is no nowcast fallback in the personalised 2D2 stream. If the SWIRLS run is stale, incompatible or unavailable, the worker fails closed/retries rather than silently changing the source contract.

## Source freshness and coverage

Before the adaptive fetch begins:

- SWIRLS run time must satisfy the existing 45-minute source freshness policy;
- the cached location must be inside the parsed SWIRLS grid;
- the worker adapter requires the cached Weather Metro location to be no older than six hours.

A stale source or out-of-grid location produces no personalised rain event.

## Notification identity

Personalised rain notifications use deterministic IDs derived from the 2D2 event identity:

```text
source + event kind + episode id + transition ordinal
```

The user-facing event is explicitly location-derived and has:

- `sourceType = HKO_SWIRLS_LOCATION_DERIVED`
- `journalCursor = 0`
- target `weathermetro://tools`
- general notification channel
- the same 90-minute post TTL already used for time-sensitive location-derived notifications

Titles/content state that the result is generated from HKO SWIRLS data by Weather Metro's local location calculation. Heavy-rain wording explicitly avoids presenting the product threshold as an HKO warning threshold.

## Important activation boundary

2D2D **does not schedule `PersonalizedRainWorker`**.

There is no new periodic WorkManager job in this checkpoint, and application startup behaviour is unchanged.

This is deliberate: runtime/data-ordering tests should pass before a second persistent location-derived network worker is enabled.

The existing territory-wide HKO warning one-minute detection path is unchanged.

## Next checkpoint

Recommended Phase 2D2E:

1. decide activation/cadence and whether to share/coalesce execution with the existing 15-minute location-heavy-rain worker;
2. expose a dedicated personalised rain-forecast setting rather than overloading the existing 2D1 `location heavy rain` switch;
3. wire immediate evaluation to an already-resolved host-location refresh;
4. add scheduler/reset tests and production verification;
5. keep lightning production blocked until its machine-readable source contract is resolved.

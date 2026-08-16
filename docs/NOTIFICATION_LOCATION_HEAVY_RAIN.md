# Notification Phase 2D1 — Location-specific heavy rain

## Scope

This checkpoint adds a Weather Metro **location-derived** heavy-rain notification. It is not presented as a verbatim MyObservatory push and does not claim to reproduce HKO's private push backend.

The signal is derived on the Android device from two inputs already owned by Weather Metro:

1. the most recent precise host location resolved by Weather Metro; and
2. Hong Kong Observatory `rhrread` district rainfall data.

No location is uploaded to the Weather Metro notification backend.

## Threshold model

The local episode evaluator uses two past-hour district rainfall levels:

- **50 mm** — location heavy-rain notification;
- **70 mm** — higher-level location heavy-rain notification.

The HKO district row's `max` value is treated as the maximum rainfall recorded in that district during the past 60 minutes.

Notification semantics are upward-only within one rain episode:

- below 50 mm → no active episode;
- first crossing to 50 mm → notify once;
- escalation from 50 mm to 70 mm → notify once;
- 70 mm later falling into the 50–69.9 mm band → no downgrade notification;
- the episode resets only after the district maximum falls below 50 mm.

Missing or invalid HKO data never clears an active episode.

## Location ownership and privacy

`LocationRepository` remains the only location requester. The notification subsystem does not call Fused Location Provider itself and does not request background-location permission.

When Weather Metro successfully resolves a precise location, it stores a compact local copy containing:

- latitude / longitude;
- label / district;
- accuracy;
- local update timestamp.

The background worker uses that cached fix for at most six hours. If it is absent or older than six hours, location heavy-rain notification fails closed until Weather Metro obtains a fresh precise location.

Turning off precise location clears the cached personalized-notification location and resets the active heavy-rain episode.

## Scheduling

The worker uses Android WorkManager:

- periodic network-connected check every 15 minutes;
- immediate expedited check after a fresh host location is recorded, app startup, or notification enablement;
- exponential retry after network/source failure.

Android may defer periodic work. This feature therefore improves personalized coverage but does not promise an exact delivery time.

## Durable local delivery

When a threshold transition is detected:

1. the pending transition is synchronously persisted locally;
2. a deterministic notification event is inserted into the existing durable local notification inbox;
3. the transition is marked completed only after the inbox accepts it.

If the process stops between steps, the next worker replays the same deterministic event instead of losing the notification.

Events use:

- `eventKind = LOCATION_HEAVY_RAIN`;
- `sourceType = HKO_LOCATION_DERIVED`;
- `LOC_RAIN_50` / `LOC_RAIN_70` alert codes;
- the normal Weather Metro general / urgent notification channels.

The notification UI labels these as **derived from HKO public data**, rather than "HKO official content".

## User controls

The master `weather notifications` switch remains the global owner.

A second `location heavy rain` switch controls this personalized stream. The feature also requires `precise location` to remain enabled.

Disabling the master notification switch, the location-heavy-rain switch, or precise location cancels its background worker and resets the local rain episode so a later re-enable does not inherit stale episode state.

## Deliberate limitations

This checkpoint covers only the documented district heavy-rain thresholds. It does not yet implement the separate MyObservatory-style **Location-based Rain and Lightning Forecast** stream.

The next personalized-notification checkpoint should evaluate Weather Metro's integrated Rain/SWIRLS data and HKO public lightning-nowcast sources, with separate dedupe, freshness, and user controls rather than conflating them with official warning publications.

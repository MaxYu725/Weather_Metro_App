# Notification Phase 2C4 — Apps Script quota hardening

## Problem

The reliable notification backend had grown into four independent one-minute Apps Script triggers:

- durable HKO JSON journal polling;
- JSON/RSS source redundancy checking;
- detailed-RSS recovery evidence;
- guarded recovery failover.

That architecture is functionally redundant but wasteful on a consumer Google account because every time-driven invocation contributes to trigger runtime. The recovery layers also spent executions waking up only to determine that the normal source state was `MATCH`.

## Single supervisor owner

`NotificationSupervisor.gs` becomes the only production time-trigger owner.

`runNotificationSupervisor()` executes this sequence:

1. run the authoritative HKO JSON journal poll every supervisor cycle;
2. run the warning-summary RSS cross-check only when the previous cross-check is at least 110 seconds old;
3. run detailed recovery evidence only after a persistent `SECONDARY_ONLY` / `DIVERGED` gap reaches streak 2;
4. run guarded failover only when recovery evidence is required and failover is enabled;
5. refresh pipeline health;
6. record measured supervisor runtime.

The primary journal therefore remains at one-minute trigger cadence. Source redundancy becomes approximately two-minute steady-state sampling, while expensive recovery work is event-driven by an actual persistent source gap.

## Trigger migration

`setupNotificationSupervisor()` removes these notification-owned legacy triggers:

- `checkWeatherUpdates`
- `checkWeatherUpdatesJournalled`
- `checkWarningSourceRedundancy`
- `checkSourceGapRecoveryEvidence`
- `checkSourceGapRecoveryFailover`

and installs exactly one one-minute `runNotificationSupervisor` trigger.

Every supervisor cycle also removes accidentally recreated legacy notification triggers. This makes migration self-healing if an older setup helper is run later.

The legacy functions remain callable manually for diagnostics and backwards-compatible setup/recovery work; they are no longer intended to own production scheduling after 2C4.

## Runtime telemetry

Supervisor runtime is stored as compact Script Properties metadata only. It records:

- Hong Kong day key;
- executions today;
- measured trigger runtime today;
- average / maximum execution duration;
- source checks today;
- recovery checks today;
- overlapping-cycle skips;
- component failures;
- removed legacy triggers.

The health model also exposes a **conservative consumer-account reference** of 90 trigger-runtime minutes per day. It reports:

- measured daily runtime;
- projected daily runtime after at least 10 observed cycles;
- `consumerQuotaRisk` when measured/projected usage approaches that reference;
- `QUOTA_RUNTIME_HIGH` only when actual measured runtime passes the hard local warning threshold.

This is operational telemetry, not a Google quota API. Google can change quotas and Workspace accounts can have different limits.

## Failure isolation

The supervisor does not make FCM the source of truth and does not weaken journal ordering:

`HKO -> Google Sheets journal -> durable outbox -> FCM wake-up -> Android journal reconciliation`

A journal component failure is recorded but does not prevent the supervisor from attempting the independent source cross-check. Recovery failover still uses its existing script lock and independent revalidation gates.

A user lock prevents overlapping supervisor executions. If a previous cycle is still running, the later cycle records `SKIPPED_BUSY`; pipeline health will still detect stale primary polling if this becomes persistent.

## Production verification

After updating the Apps Script project, run:

`setupNotificationSupervisor()`

Then run:

`verifyNotificationSupervisor()`

Expected steady state:

- `supervisorTriggerCount = 1`
- `legacyTriggerCount = 0`
- pipeline `status = HEALTHY`
- `journalTriggerCount = 0`
- `sourceTriggerCount = 0`
- `pendingOutboxEvents = 0`

`journalTriggerCount` and `sourceTriggerCount` intentionally become zero because those functions are now called by the supervisor rather than by their own time triggers.

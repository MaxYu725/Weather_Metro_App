# Notification quota runtime hardening — Phase 2C4B/2C4C

## Production evidence

Phase 2C4 first consolidated four one-minute Apps Script triggers into one
supervisor. The first production soak collected 21 real executions:

- average supervisor runtime: **5,973 ms**
- projected 24-hour trigger runtime: **8,601,394 ms** (~143 minutes)
- consumer reference budget: **5,400,000 ms** (90 minutes)
- `consumerQuotaRisk=true`
- `projectedRuntimeRisk=true`
- no busy skips and no component failures

2C4B then introduced the one-minute `warnsum` fast path and bounded auxiliary
hydration. The next production soak collected 18 executions:

- fast cycles: **12**
- full cycles: **6**
- source cross-checks: **5**
- average supervisor runtime: **3,831 ms**
- projected 24-hour trigger runtime: **5,516,080 ms** (~91.9 minutes)
- `consumerQuotaRisk=true`
- `projectedRuntimeRisk=true`
- zero journal failures, busy skips and component failures

2C4B therefore removed most quota pressure but remained above the consumer
reference. Territory-wide warning polling is not slowed to hide that result.

## Runtime model

The one-minute supervisor remains. Territory-wide warning detection does **not**
move to a slower trigger.

### Every minute

The supervisor fetches HKO `warnsum` and computes a stable digest from official
warning family/code/action/timestamps/title fields.

If that digest changes, a full authoritative journal pass runs immediately. This
keeps territory-wide ISSUE/UPDATE/EXTEND/REISSUE/CANCEL detection on the one-minute
path.

### Bounded auxiliary hydration

When `warnsum` is unchanged, `warningInfo` + SWT hydration runs at least every
170 seconds. This covers standalone `warningInfo` statements such as WTCPRE8 and
Special Weather Tips that can appear without a `warnsum` transition.

Their maximum normal detection delay remains about three minutes plus Apps Script
trigger jitter. The committed warnsum digest advances only after full hydration
succeeds; a failure retries on the next supervisor cycle.

## 2C4C: reuse the minute's authoritative warnsum

The 2C4B full cycle still performed a second `warnsum` request inside the legacy
journal owner even though the supervisor had fetched the same source moments
earlier. 2C4C removes that duplicate work without changing cadence.

`NotificationJournalHydration.gs` accepts the already-fetched warnsum object and
fetches only `warningInfo` and `swt`, then preserves the same durable sequence:

`source publications -> Google Sheets journal -> durable outbox -> FCM wake-up`

The outbox is still persisted before source state and deterministic event IDs
still protect crash recovery.

Further steady-state savings:

- unchanged normalized source state is not rewritten every full hydration;
- no second outbox flush/write occurs when no new event exists;
- successful hydration does not recompute full pipeline health;
- Spreadsheet access remains skipped when there is no new event.

The fast-poll state moves V1 -> V2. On first V2 execution only the supervisor
runtime telemetry is reset for a clean post-2C4C soak. Durable journal state,
outbox and journal cursor are never reset.

## FCM outbox and source redundancy

Fast-only cycles continue to retry any durable FCM outbox immediately.

The RSS cross-check continues to reuse the current minute's warnsum. If RSS is
ahead, `retryPrimarySourceGap_()` still performs fresh cache-busted JSON reads
before evidence/failover can run. Healthy parity remains sampled around every
170 seconds and degraded parity on the next minute.

## Production migration for 2C4C

Update the existing Apps Script project with:

1. add `NotificationJournalHydration.gs`
2. replace `NotificationFastPoll.gs`
3. keep `NotificationSupervisor.gs` and every other notification file unchanged
4. run `setupNotificationSupervisor()` once
5. wait for at least 10 automatic runs
6. run `verifyNotificationSupervisor()`
7. only after the runtime soak passes, update the existing Web App deployment to
   a new version while preserving the same `/exec` URL and verify health

Expected immediate state:

- `supervisorTriggerCount=1`
- `legacyTriggerCount=0`
- pipeline `status=HEALTHY`
- `fastPoll.schemaVersion=2`
- `fastPoll.optimizedHydrationAvailable=true`
- `fastPoll.committedSummaryDigestPresent=true`
- `pendingOutboxEvents=0`

Expected post-soak gate:

- `dayRunCount >= 10`
- `consumerQuotaRisk=false`
- `projectedRuntimeRisk=false`
- `journalFailuresToday=0`
- `busySkipsToday=0`
- `componentFailuresToday=0`

CI must pass all Apps Script tests, including prefetched hydration request shape,
state-write suppression, and durable outbox-before-state ordering, plus the
existing Android unit/lint/assemble gate.

If measured runtime still exceeds the consumer reference after duplicate source
and state work is removed, move server-side polling to a runtime with a more
suitable execution budget rather than weakening the one-minute territory-wide
warning path.

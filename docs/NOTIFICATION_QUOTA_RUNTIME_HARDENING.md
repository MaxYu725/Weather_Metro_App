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

2C4B therefore removed most of the quota pressure but remained slightly above the
consumer-account reference budget. Territory-wide warning polling is not slowed
to hide that result.

## Runtime model

The one-minute supervisor trigger remains. Territory-wide warning detection does
**not** move to a slower trigger.

### Every minute

The supervisor fetches HKO `warnsum` and computes a stable digest from the
official warning family/code/action/timestamps/title fields.

If that digest changes, a full authoritative journal pass runs immediately. This
keeps territory-wide warning ISSUE/UPDATE/EXTEND/REISSUE/CANCEL detection on the
one-minute path.

### Bounded auxiliary hydration

When `warnsum` is unchanged, `warningInfo` + SWT hydration runs at least every
170 seconds. This is needed because HKO can publish standalone `warningInfo`
statements (for example WTCPRE8) or Special Weather Tips without a `warnsum`
state transition.

Their maximum normal detection delay remains approximately three minutes plus
Apps Script trigger jitter. The committed warnsum digest advances only after
full hydration succeeds; a failure is retried on the next supervisor cycle.

## 2C4C: reuse the minute's authoritative warnsum

The 2C4B full cycle still performed a second `warnsum` request inside
`checkWeatherUpdatesJournalled()`, even though the supervisor had fetched the same
authoritative source moments earlier. 2C4C removes that duplicate work without
changing detection cadence.

`NotificationJournalHydration.gs` now accepts the already-fetched warnsum object
and fetches only:

- `warningInfo`
- `swt`

It then runs the same durable sequence:

`source publications -> Google Sheets journal -> durable outbox -> FCM wake-up`

The outbox is still persisted before source state, and deterministic event IDs
still protect crash recovery.

Additional steady-state savings:

- if full hydration finds no new publication and the normalized source state is
  unchanged, it does not rewrite the same journal state properties;
- if no new event is queued, it does not perform a second outbox flush/write;
- successful full hydration does not recompute the full pipeline-health snapshot;
  verify/Web App health still derive it live, while supervisor failures refresh
  health immediately;
- Spreadsheet access remains skipped when there is no new event.

The fast-poll state moves from V1 to V2. On the first V2 run, only the supervisor
runtime telemetry is reset for a clean soak. Durable journal state and journal
cursor are not reset.

## FCM outbox

A fast-only cycle still checks for an existing durable FCM outbox. Pending wake-up
messages are retried immediately and do not wait for the next full HKO hydration.

## Independent source cross-check

The normal RSS cross-check reuses the warnsum object already fetched during the
same supervisor cycle, so a healthy MATCH needs only the RSS request instead of a
second primary JSON request.

If RSS is ahead of JSON, `retryPrimarySourceGap_()` still makes fresh cache-busted
primary reads before a persistent gap can advance to evidence or failover.
Healthy source parity is sampled about every 170 seconds; degraded parity is
rechecked on the next minute.

## Production migration for 2C4C

Update the existing Apps Script project with:

1. add `NotificationJournalHydration.gs`
2. replace `NotificationFastPoll.gs`
3. keep `NotificationSupervisor.gs` and all other notification files unchanged
4. run `setupNotificationSupervisor()` once
5. wait for at least 10 automatic runs
6. run `verifyNotificationSupervisor()`
7. after the runtime soak passes, update the existing Web App deployment to a new
   version while preserving the same `/exec` URL and verify `/exec?mode=health`

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

If measured runtime still exceeds the consumer reference after removing duplicate
source and state work, the next architecture step should move the server-side
polling workload to a scheduler/runtime with a more suitable execution budget,
rather than weakening the one-minute territory-wide warning path.

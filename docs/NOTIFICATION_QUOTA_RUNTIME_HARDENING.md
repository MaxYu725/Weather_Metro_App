# Notification quota runtime hardening — Phase 2C4B

## Production evidence that triggered this checkpoint

After Phase 2C4 consolidated four one-minute Apps Script triggers into one
supervisor, production telemetry collected 21 real executions on 2026-08-16:

- average supervisor runtime: **5,973 ms**
- projected 24-hour trigger runtime: **8,601,394 ms** (~143 minutes)
- consumer reference budget used by the monitor: **5,400,000 ms** (90 minutes)
- `consumerQuotaRisk=true`
- `projectedRuntimeRisk=true`
- no busy skips and no component failures

The notification pipeline itself remained healthy. The issue was projected daily
Apps Script trigger runtime, not FCM throughput or Android delivery.

## 2C4B runtime model

The one-minute supervisor trigger remains. Territory-wide warning detection does
**not** move to a slower trigger.

### Every minute

The supervisor fetches HKO `warnsum` only and computes a stable digest from the
official warning family/code/action/timestamps/title fields.

If that digest changes, a full authoritative journal pass runs immediately. This
keeps territory-wide warning ISSUE/UPDATE/EXTEND/REISSUE/CANCEL detection on the
one-minute path.

### Bounded full hydration

When `warnsum` is unchanged, full hydration (`warnsum + warningInfo + swt`) runs
at least every 170 seconds. This is needed because HKO can publish standalone
`warningInfo` statements (for example WTCPRE8) or Special Weather Tips without a
`warnsum` state transition.

Therefore this optimization trades only the steady-state polling cost of those
auxiliary publication paths; it does not remove their coverage. Their maximum
normal detection delay is approximately three minutes plus Apps Script trigger
jitter.

The committed warnsum digest advances only after the full journal pass succeeds.
If full hydration fails, the next supervisor cycle retries it rather than treating
the fast poll as authoritative completion.

### FCM outbox

A fast-only cycle still checks for an existing durable FCM outbox. Pending wake-up
messages are retried immediately and do not wait for the next full HKO hydration.

### Independent source cross-check

The normal RSS cross-check reuses the warnsum object already fetched during the
same supervisor cycle, so a healthy MATCH needs only the RSS request instead of a
second primary JSON request.

If RSS is ahead of JSON, the existing `retryPrimarySourceGap_()` path still makes
fresh cache-busted primary reads before a persistent gap can advance to evidence
or failover. The reliability gate is unchanged.

Healthy source parity is sampled about every 170 seconds. Degraded parity is
rechecked on the next minute (~55 second minimum age).

### Health and maintenance service calls

The supervisor no longer recomputes the full health snapshot every healthy
minute. `verifyNotificationSupervisor()` and `/exec?mode=health` derive health
live, while failures still force an immediate refresh.

Legacy-trigger pruning remains self-healing but is sampled every ten supervisor
runs rather than listing project triggers every minute.

## Telemetry reset

2C4B writes supervisor runtime to `HKO_NOTIFICATION_SUPERVISOR_RUNTIME_V2`.
This intentionally starts a clean post-optimization sample instead of mixing the
old 5.973-second average into the new projection.

New counters separate:

- fast journal checks
- full journal checks
- journal failures
- source checks
- recovery checks
- trigger-prune checks
- busy skips
- component failures

Quota projection still waits for at least 10 real runs.

## Production migration

Update the existing Apps Script project with:

1. add `NotificationFastPoll.gs`
2. replace `NotificationSupervisor.gs`
3. keep the other notification `.gs` files unchanged
4. run `setupNotificationSupervisor()` once
5. wait for at least 10 automatic runs
6. run `verifyNotificationSupervisor()`
7. update the existing Web App deployment to a new version while preserving the
   same `/exec` URL, then verify `/exec?mode=health`

Expected immediate state:

- `supervisorTriggerCount=1`
- `legacyTriggerCount=0`
- pipeline `status=HEALTHY`
- `fastPoll.committedSummaryDigestPresent=true`
- `pendingOutboxEvents=0`

Expected post-soak gate:

- `dayRunCount >= 10`
- `consumerQuotaRisk=false`
- `projectedRuntimeRisk=false`
- `busySkipsToday=0`
- `componentFailuresToday=0`

If the optimized measured projection still exceeds the consumer reference
budget, the next step is not to silently slow territory-wide warning detection.
Further work should remove additional steady-state service calls or move the
server-side polling workload to infrastructure with a more suitable execution
budget.

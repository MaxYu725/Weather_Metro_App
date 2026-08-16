# Notification Phase 2C3B — Guarded source-gap failover

## Goal

Recover a positively confirmed active HKO warning when the primary JSON warning-summary path is persistently behind, without turning RSS disappearance into a synthetic cancellation.

This checkpoint prioritises **no missed active warning** while retaining fail-closed cancellation semantics.

## Evidence gate

A recovery notification is eligible only when all of the following remain true at execution time:

1. HKO warning-summary JSON and HKO warning-summary RSS have a persistent `SECONDARY_ONLY` / `DIVERGED` gap.
2. The gap has survived at least two cross-check observations, including the bounded fresh JSON retries introduced in Phase 2C2.
3. The independent HKO detailed-warning RSS has produced `DETAIL_CONFIRMED` evidence for the same missing token.
4. Both source state and detailed evidence are fresh (maximum three minutes old).
5. A fresh detailed-warning RSS fetch still has the same digest that produced the evidence.
6. A fresh `warningInfo` JSON read does not already prove that the primary detailed JSON path contains that warning.

The detailed JSON check is used to suppress avoidable duplicate recovery. If that duplicate-suppression read itself fails, two independent RSS sources still constitute positive recovery evidence.

## Delivery semantics

A confirmed recovery is written into the same durable Google Sheets journal used by normal HKO publications **before** an FCM wake-up is sent. Therefore Android recovery remains cursor-based and does not depend on FCM arriving immediately.

Recovery events use:

- `eventKind = RECOVERY`
- `sourceType = RSS_RECOVERY`
- the official warning name as the visible title
- the matching HKO detailed-RSS item body where available
- the full HKO detailed-RSS visible text as a safe fallback if the feed groups warnings differently
- the existing Weather Metro warning channel/severity mapping

The visible notification does not add a synthetic "Weather Metro recovered this" prefix. Internal recovery metadata remains in the journal fields only.

## Idempotency

The recovery event ID is deterministic from:

`warning token + detailed RSS digest`

The backend also keeps compact sent state per active warning token. If a trigger crashes after the journal append but before FCM queueing, the next trigger can re-queue the same journal event. Android deduplicates by event ID.

The sent-state record is cleared only after both healthy warning-summary sources no longer show that token, allowing a later independent warning episode to recover again.

## Deliberate limitation: no inferred cancellation

The HKO RSS datasets are documented as warning information **in force**. Disappearance from an active-warning feed is therefore not treated as an official cancellation publication.

Phase 2C3B does **not** synthesize a `CANCEL` notification from RSS disappearance. Official cancellation/detail publications observed through the JSON `warningInfo` / `warnsum` pipeline continue to be journalled normally.

This is deliberate: receiving every positively confirmed active warning is preferable to generating a false official cancellation.

## Production activation

Add `backend/apps-script/RecoveryFailover.gs` to the existing Apps Script project, then run:

```text
setupSourceGapRecoveryFailover
```

This enables the failover property and installs exactly one one-minute trigger. When source state is currently `MATCH`, setup emits no recovery notification.

Then run:

```text
verifySourceGapRecoveryFailover
```

Expected steady state before any source gap:

- `enabled: true`
- `triggerCount: 1`
- `recoveredTokens: []`
- `status.status: IDLE`

No Web App redeployment is required. Recovery events use the existing journal `/exec` endpoint.

To disable recovery immediately without touching the primary notification system:

```text
disableSourceGapRecoveryFailover
```

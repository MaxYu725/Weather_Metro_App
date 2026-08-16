# Notification Phase 2C3A — Source-gap recovery evidence

## Why this checkpoint exists

Phase 2C1/2C2 proved that the official HKO JSON warning summary and the separately hosted HKO warning-summary RSS can be compared independently. A persistent `SECONDARY_ONLY` result is evidence that the RSS path is advertising an active warning absent from the JSON summary after bounded retries.

That signal is not yet sufficient to manufacture a user-visible alert. Summary RSS has less publication metadata than the JSON `warnsum + warningInfo` path, and blindly converting it to an alert could create duplicates or stale semantics.

## 2C3A design

`RecoveryEvidence.gs` adds a third official HKO publication path:

- summary JSON: `warnsum`
- summary RSS: `WeatherWarningSummaryv2_uc.xml`
- detailed warning RSS: `WeatherWarningBulletin_uc.xml`

The detailed RSS is queried only after the summary cross-check has a persistent RSS-only warning (`SECONDARY_ONLY`/`DIVERGED`, streak >= 2). The result is reduced to compact evidence metadata:

- required warning tokens from the summary mismatch
- detailed-RSS warning tokens
- confirmed/missing tokens
- digest of the detailed bulletin
- error/status metadata

No HKO bulletin body is written to Script Properties, no event is appended to the notification journal, and no FCM message is sent by this checkpoint.

Statuses:

- `IDLE` — no persistent source gap
- `DETAIL_CONFIRMED` — every RSS-only token is independently present in detailed HKO RSS
- `DETAIL_PARTIAL` — only some tokens are independently present
- `DETAIL_MISSING` — detailed RSS does not confirm the summary mismatch
- `DETAIL_ERROR` — detailed RSS could not be read safely

## Journal cursor telemetry repair

Production verification of 2C2 exposed an observability-only edge: if health instrumentation is installed after the journal already contains rows, `latestJournalCursor` remains zero until the next append.

`setupSourceGapRecoveryEvidence()` and `verifySourceGapRecoveryEvidence()` now read the authoritative Google Sheet tail and seed the health runtime cursor monotonically. This does not affect delivery cursors or Android reconciliation; it only repairs backend health telemetry.

## Production activation

1. Add `backend/apps-script/RecoveryEvidence.gs` to the existing Apps Script project.
2. Run `setupSourceGapRecoveryEvidence()` once.
3. Run `verifySourceGapRecoveryEvidence()`.
4. Confirm `triggerCount = 1`.
5. Confirm `authoritativeJournalCursor` matches the existing journal tail (currently expected to be non-zero if the production journal already contains an event).
6. Normal steady state should show `evidence.status = IDLE` while JSON and summary RSS remain matched.

This checkpoint does not require changing the existing Web App `/exec` deployment because it does not alter the public endpoint.

## Gate before automatic failover

Automatic user-visible recovery from RSS should only be considered after production evidence demonstrates how real HKO source gaps behave. A recovery notification must have deterministic cross-source deduplication and must not suppress a later official JSON `ISSUE`, `UPDATE`, `EXTEND`, `REISSUE`, or `CANCEL` publication incorrectly.

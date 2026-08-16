# Apps Script alert monitor

This folder contains the server-side one-minute HKO publication monitor. It uses
the official `warnsum`, `warningInfo`, and `swt` endpoints and treats the fields
published by HKO as the source of truth instead of reconstructing warning actions
from an active-state diff.

The current source-publication contract preserves `warnsum.actionCode` values
such as `ISSUE`, `REISSUE`, `CANCEL`, `EXTEND`, and `UPDATE`. It also records
non-empty `warningInfo` statements that do not have a matching `warnsum` row
(including `WTCPRE8`) and Special Weather Tips. A publication disappearing from
a later snapshot does **not** create a synthetic cancellation; cancellation is
only emitted when HKO publishes it.

Official detail text keeps its line breaks. Notification titles no longer add
Weather Metro-generated `已發出` / `已更新` / `已取消` prefixes. Each observed
source publication receives a deterministic ID derived from its source type,
warning family/code, official action, official timestamps, title, and body.

FCM delivery still uses the durable Script Properties outbox introduced by the
previous reliability phase. Failed sends remain queued with exponential-backoff
retry metadata until FCM accepts them. Each outbox event uses its own property
plus a small index, staying below Apps Script's 9 KB per-value limit.

## Reliability boundary

This checkpoint fixes **source-event fidelity**. FCM remains an immediate-delivery
transport, not a receipt guarantee. The FCM preview body is byte-bounded, so the
next reliability checkpoint must add a durable full-text publication journal and
client cursor reconciliation. That journal will let Android fetch any event it did
not receive through FCM and will be the authoritative source for complete HKO
content.

## One-time owner setup

1. Create or open a Google Apps Script project, enable the manifest in project
   settings, and copy `Code.gs` and `appsscript.json` into it.
2. Add these **Script Properties** (do not paste values into source code):
   `FIREBASE_PROJECT_ID`, `FIREBASE_CLIENT_EMAIL`, and `FIREBASE_PRIVATE_KEY`.
   Use a newly generated service-account key with permission to send Firebase
   Cloud Messaging messages. A multiline private key or a value containing
   literal `\\n` line breaks is accepted.
3. Run `sendTestNotification` once and approve the requested permissions.
4. Run `installOneMinuteTrigger` once. It removes duplicate monitor triggers
   and installs exactly one one-minute trigger. The old
   `installFiveMinuteTrigger` name remains as a compatibility alias.

The V6 source-publication state intentionally starts a new baseline. On the first
`checkWeatherUpdates` run after upgrading from V3/V4/V5, every HKO publication
still visible is emitted once. This favours avoiding a missed live warning over a
silent migration that could incorrectly suppress an official publication.

Alert events use Android high priority, a 24-hour FCM TTL, and no collapse key.
The deterministic event ID allows the Android inbox to suppress duplicates when
the server retries an accepted event whose response was lost.

## Operations

- Run `resetAlertBaseline` if the saved state is corrupt. The next run reissues
  every HKO publication currently visible.
- Inspect **Executions** in Apps Script for HKO, OAuth, FCM, or outbox errors.
  Failed trigger executions also generate Apps Script owner failure notices.
- Run `node --test backend/apps-script/Code.test.mjs` before deploying changes.
- Rotate the service-account key in Google Cloud and replace only the Script
  Property value; no repository change is needed.

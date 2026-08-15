# Apps Script alert monitor

This folder contains the server-side one-minute HKO alert monitor. It uses the
official `warnsum`, `warningInfo`, and `swt` endpoints, stores a stable state
snapshot, and sends issue/update/cancel changes through FCM HTTP v1. Events are
written to a durable Script Properties outbox before delivery and retained with
exponential-backoff retry metadata until FCM accepts them. Each event uses its
own property plus a small index, staying below Apps Script's 9 KB per-value limit.
The baseline uses the same per-alert layout and stores a bounded body excerpt
while retaining a fingerprint of the complete HKO text.
Notifications are data-only so Android always builds the expandable notification
and routes taps to the matching alert tile. HKO text is truncated to a safe UTF-8
payload excerpt; opening the notification refreshes the complete official content.

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

The first `checkWeatherUpdates` run sends every alert currently in force. This
prevents a new or reset deployment from silently missing an important warning.
Upgrading from the V3 or V4 state key preserves the existing baseline and does not
resend unchanged alerts.

Alert events use Android high priority, a 24-hour FCM TTL, and no collapse key.
The deterministic event ID allows the Android inbox to suppress duplicates when
the server retries an accepted event whose response was lost.

## Operations

- Run `resetAlertBaseline` if the saved state is corrupt. The next run reissues
  every alert still in force.
- Inspect **Executions** in Apps Script for HKO, OAuth, FCM, or outbox errors.
  Failed trigger executions also generate Apps Script owner failure notices.
- Run `node --test backend/apps-script/Code.test.mjs` before deploying changes.
- Rotate the service-account key in Google Cloud and replace only the Script
  Property value; no repository change is needed.

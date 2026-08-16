# Apps Script reliable HKO notification monitor

Weather Metro treats Hong Kong Observatory publications as the source of truth.
The backend polls the official `warnsum`, `warningInfo`, and `swt` endpoints every
minute, preserves HKO action/time/content semantics, appends each newly observed
publication to a durable Google Sheets journal, and only then queues an FCM wake-up.

FCM is therefore the **fast path**, not the only copy of an event. Android fetches
the authoritative journal by cursor with WorkManager. If an FCM message is missed,
the next app start/resume or the periodic recovery worker can still fetch every
journal event that follows the device's last durable cursor.

## Source-publication contract

The backend preserves `warnsum.actionCode` values such as `ISSUE`, `REISSUE`,
`CANCEL`, `EXTEND`, and `UPDATE`. It also keeps non-empty `warningInfo` statements
that do not have a matching `warnsum` row (including `WTCPRE8`) and Special
Weather Tips.

A publication disappearing from a later HKO snapshot does **not** create a
Weather Metro-invented cancellation. Cancellation is emitted only when HKO
publishes cancellation semantics.

Official detail line breaks are retained. Notification titles no longer add
Weather Metro-generated `已發出` / `已更新` / `已取消` prefixes. The WTCPRE8 title is
normalised to the HKO wording `預警八號熱帶氣旋警告信號特別報告`.

When the same WTCPRE8 body is simultaneously surfaced through `warningInfo` and
Special Weather Tips, it is journalled once rather than presenting the same HKO
message twice to the user.

## Reliability model

The durable path is:

`HKO JSON -> immutable publication ID -> Google Sheets journal -> FCM wake-up -> Android WorkManager -> local inbox -> system notification`

Important ordering rules:

1. The complete HKO event is appended to the journal before FCM is queued.
2. The FCM outbox is persisted before source state advances.
3. The Android cursor advances only after the complete event is durably committed
   to the local inbox.
4. Invalid/corrupt journal rows fail closed: Android does not skip the cursor.
5. An older FCM preview with the same event ID is upgraded by the complete journal
   event and reposted with the authoritative content.

The FCM body remains a byte-bounded preview for compatibility, but schema v4
messages also carry `journalUrl` and `journalCursor`. A journal-capable Android
build does not display that preview when it can fetch the authoritative event.

The Google Sheet contains only already-public HKO publication content and event
metadata. Firebase service-account credentials remain in Script Properties and
are never exposed through `doGet`.

## One-time owner setup

### 1. Copy the Apps Script files

Create or open the Apps Script project and copy:

- `Code.gs`
- `Journal.gs`
- `appsscript.json`

Enable the manifest in Apps Script project settings if needed.

### 2. Configure Firebase credentials

Add these **Script Properties**; never paste values into source code:

- `FIREBASE_PROJECT_ID`
- `FIREBASE_CLIENT_EMAIL`
- `FIREBASE_PRIVATE_KEY`

Use a service-account key that is allowed to send Firebase Cloud Messaging HTTP
v1 messages. A multiline private key or a value containing literal `\\n` line
breaks is accepted.

### 3. Authorise and create the journal

Run `setupReliableNotifications()` once from the Apps Script editor and approve
the requested permissions. It creates a spreadsheet named
`Weather Metro Notification Journal`, stores only its ID in Script Properties,
and installs one one-minute `checkWeatherUpdatesJournalled` trigger.

The manifest now requires the Google Sheets scope because the complete event
journal is intentionally not stored in Script Properties.

### 4. Deploy the read-only journal API

Deploy the Apps Script project as a **Web app**:

- execute as: the script owner
- access: anyone who can reach the public endpoint (no sign-in required by the app)

The endpoint exposes only public HKO publication events. It does not expose
Firebase credentials or Script Properties.

After deploying, run `setupReliableNotifications()` again, then run
`verifyReliableNotificationSetup()`. Verify that:

- `journalUrl` is a non-empty `https://script.google.com/macros/s/.../exec` URL
- `triggerCount` is exactly `1`
- `pendingOutboxEvents` is normally `0`

The journal API is:

`GET <journalUrl>?after=<cursor>&limit=<1..200>`

and returns `nextCursor`, `latestCursor`, `hasMore`, and the complete ordered
`events` array.

### 5. Configure the production Android build

Set `WEATHER_NOTIFICATION_JOURNAL_URL` to the production `/exec` URL as either a
Gradle property or build environment variable. Debug/CI builds may leave it blank.

FCM schema v4 also sends the URL and Android caches it, but the build-time URL is
important for a fresh installation: it lets the app reconcile the journal even
if the **first** FCM message is the one that is missed.

### 6. Run a setup verification

Run `verifyReliableNotificationSetup()` after deployment or credential changes.
Inspect Apps Script **Executions** for HKO, Sheets, OAuth, FCM, or outbox failures.

## Android recovery behaviour

When notifications are enabled:

- app startup schedules journal reconciliation
- app resume schedules journal reconciliation
- schema v4 FCM schedules an expedited reconciliation
- `onDeletedMessages()` schedules a full reconciliation
- a network-connected WorkManager safety-net runs periodically
- pending local notifications are replayed after notification permission/channel
  access is restored

The safety-net is recovery, not a replacement for FCM's immediate fast path.

## Tests

Before deployment:

```text
node --test backend/apps-script/*.test.mjs
./gradlew testDebugUnitTest lintDebug assembleDebug
```

CI runs both suites for `main`, `agent/**`, and pull requests.

## Operational boundary

No Android application can force the OS to display a notification while the app
is force-stopped, the device remains offline, or the user/OS has blocked the app
or notification channel. Weather Metro's enforceable target is therefore:

> Every HKO publication detected by the backend is durably journalled before
> delivery is attempted, and every enabled installation eventually retrieves
> every journal event once it again has network/execution capability. Events
> that cannot currently be posted remain in the local inbox for replay.

This is materially stronger than treating FCM acceptance as proof of delivery.

## Scope note

This journal covers the territory-wide official publication stream currently
integrated by Weather Metro: weather warnings, warning information/statements,
and Special Weather Tips. HKO also offers personalised location-based rain,
lightning, and location-specific heavy-rain notifications. Those depend on a
user/device location stream and should be integrated separately with Weather
Metro's Rain/location owner rather than duplicated in this global warning journal.

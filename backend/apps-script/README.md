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

`HKO JSON -> immutable publication ID -> Google Sheets journal -> FCM system preview + reconciliation metadata -> Android full-text reconciliation`

Important ordering rules:

1. The complete HKO event is appended to the journal before FCM is queued.
2. The FCM outbox is persisted before source state advances.
3. The Android cursor advances only after the complete event is durably committed
   to the local inbox.
4. Invalid/corrupt journal rows fail closed: Android does not skip the cursor.
5. Google Play services can post the FCM preview while the app is backgrounded;
   the foreground service uses the same stable tag/ID. The complete journal event
   upgrades that item, so app-process or endpoint delay cannot suppress visibility.
6. The client probes distinct cached/configured journal URLs and keeps the live
   page with the newest cursor, healing a deleted or frozen Apps Script deployment.

The FCM notification body is capped at 300 UTF-8 bytes and the complete request
is tested below the 2,048-byte topic limit. Schema v4 data carries journal
metadata without duplicating title/body. A journal-capable Android build replaces
the preview from the authoritative journal under the same event tag and numeric
ID. The update uses `setOnlyAlertOnce`, so full-text reconciliation does not buzz
a second time. Android notification proxy mode is explicitly `ALLOW`, asking
Google Play services to display the system notification without first starting
the Weather Metro process.

The Google Sheet contains only already-public HKO publication content and event
metadata. Firebase service-account credentials remain in Script Properties and
are never exposed through `doGet`.

## Production schedule and Apps Script quota hardening

`NotificationSupervisor.gs` is the **only production time-trigger owner**.
`runNotificationSupervisor` runs every minute and:

1. executes the authoritative HKO JSON journal poll every cycle;
2. samples the independent warning-summary RSS cross-check about every two minutes;
3. runs detailed recovery evidence only after a persistent source gap reaches streak 2;
4. runs guarded recovery failover only when that evidence is needed and failover is enabled;
5. records measured runtime and refreshes pipeline health.

This replaces the previous independent one-minute journal/source/evidence/failover
triggers. The old functions remain callable manually, but their time triggers must
be absent in production after migration.

The supervisor records a conservative consumer-account runtime projection. This
is local telemetry, not a Google quota API; Google can change quotas and Workspace
accounts can have different limits. See `docs/NOTIFICATION_APPS_SCRIPT_QUOTA.md`.

## One-time owner setup

### 1. Copy the Apps Script files

Create or open the Apps Script project and copy:

- `Code.gs`
- `Journal.gs`
- `PipelineHealth.gs`
- `SourceRedundancy.gs`
- `RecoveryEvidence.gs`
- `RecoveryFailover.gs`
- `NotificationSupervisor.gs`
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
`Weather Metro Notification Journal` and stores only its ID in Script Properties.

For an existing deployment this compatibility setup can temporarily create its
legacy journal trigger. The final `setupNotificationSupervisor()` step below
removes every notification-owned legacy trigger.

The manifest requires the Google Sheets scope because the complete event journal
is intentionally not stored in Script Properties.

### 4. Deploy the read-only journal API

Deploy the Apps Script project as a **Web app**:

- execute as: the script owner
- access: anyone who can reach the public endpoint (no sign-in required by the app)

The endpoint exposes only public HKO publication events. It does not expose
Firebase credentials or Script Properties.

After deploying, run `setupReliableNotifications()` again if the deployment URL
was not previously available, then verify that `journalUrl` is a non-empty
`https://script.google.com/macros/s/.../exec` URL and `pendingOutboxEvents` is
normally `0`.

The journal API is:

`GET <journalUrl>?after=<cursor>&limit=<1..200>`

and returns `nextCursor`, `latestCursor`, `hasMore`, and the complete ordered
`events` array.

### 5. Install the single production trigger

If guarded RSS recovery has never been enabled for this Apps Script project, run
`setupSourceGapRecoveryFailover()` once first. It may temporarily create its old
standalone trigger.

Then run:

`setupNotificationSupervisor()`

This removes all notification-owned legacy triggers and installs exactly one
one-minute `runNotificationSupervisor` trigger.

Run:

`verifyNotificationSupervisor()`

Expected steady state:

- `supervisorTriggerCount = 1`
- `legacyTriggerCount = 0`
- `pipelineHealth.status = HEALTHY`
- `pipelineHealth.journalTriggerCount = 0`
- `pipelineHealth.sourceTriggerCount = 0`
- `pipelineHealth.pendingOutboxEvents = 0`

The zero journal/source trigger counts are intentional: those functions are now
called by the supervisor instead of owning separate time triggers.

### 6. Configure the production Android build

Set `WEATHER_NOTIFICATION_JOURNAL_URL` to the production `/exec` URL as either a
Gradle property or build environment variable. The repository also has the
current production endpoint as its default build value.

FCM schema v4 also sends the URL and Android caches it, but the build-time URL is
important for a fresh installation: it lets the app reconcile the journal even
if the **first** FCM message is the one that is missed. Android retains both
distinct candidates until a successful fetch identifies the newest live journal;
a cached 404/frozen deployment therefore cannot permanently pin reconciliation.

### 7. Run health verification

Run `verifyNotificationPipelineHealth()` and `verifyNotificationSupervisor()`
after deployment, credential changes, or trigger migration. Inspect Apps Script
**Executions** for HKO, Sheets, OAuth, FCM, outbox, supervisor-busy, or quota-risk
signals.

The public health endpoint remains:

`GET <journalUrl>?mode=health`

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

The global journal covers territory-wide official publication streams: weather
warnings, warning information/statements, and Special Weather Tips. Personalized
location heavy-rain notifications are evaluated locally on Android from HKO
public district rainfall data. Location-based rain/lightning forecast remains a
separate personalized stream rather than being mixed into the global journal.

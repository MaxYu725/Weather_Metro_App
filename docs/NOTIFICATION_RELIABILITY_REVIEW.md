# Notification reliability review

Reviewed: 2026-08-25

## Outcome

The original pipeline could permanently miss an HKO event after one failed FCM
request, reject long topic messages, replace older undelivered events through a
collapse key, and discard Android messages while notification permission was
blocked. The implemented design now provides durable at-least-once processing
on both sides of FCM, deterministic deduplication, and permission recovery.

Absolute delivery cannot be guaranteed by an Android app: the user can block a
channel, force-stop the app, remove network access, or keep a device offline
beyond the FCM lifetime. FCM topic messaging is also designed for throughput
rather than the lowest possible latency. The remaining architectural options
for a stronger service are listed below.

## Fixed findings

| Severity | Previous failure mode | Resolution |
| --- | --- | --- |
| Critical | Schema-v4 FCM was treated only as a wake-up. If the cached Apps Script deployment returned 404, Android discarded the usable FCM preview and displayed nothing. | Commit/post the preview immediately, then upgrade the same stable event ID from the journal. Full-text recovery can fail without suppressing visible delivery. |
| Critical | Android trusted one cached journal URL indefinitely. A deleted or frozen deployment stopped every official warning/tip reconciliation. | Probe every distinct FCM-cached and build-configured endpoint, select the live page with the newest cursor, and persist the recovered endpoint. |
| Critical | A transient FCM error followed by an HKO state change could lose the event forever. | Persist each deterministic event to an outbox before advancing alert state; retry with exponential backoff. |
| Critical | Topic payloads may only be 2,048 bytes, but complete warning bodies were embedded. | Bound title/body by UTF-8 bytes and keep ample envelope headroom. |
| Critical | A single outbox or state JSON value could exceed Apps Script's 9 KB property limit. | Store one alert/event per property with a separate bounded index. |
| High | `collapse_key` could replace an older undelivered issue/update/cancel event. | Remove the custom collapse key and make the journal authoritative. The new system-display notification copy is collapsible by FCM design, but cursor reconciliation recovers every ordered event. |
| High | A one-hour TTL discarded warnings after a moderately long offline period. | Extend TTL to 24 hours and reconcile current weather when FCM reports deleted pending messages. |
| High | Normal-priority alerts could be delayed during Android Doze. | Use high priority for every user-visible warning/tip; keep only service-status tests normal. |
| High | Data-only FCM still depended on Android starting the app's messaging service. On the affected device, no background callback ran even with unrestricted battery use; opening the app was what recovered cursors `86–101`. | Send a combined notification + data payload. Google Play services can put the preview in the system tray while the app is backgrounded; data metadata and the durable journal remain available for reconciliation. |
| High | First deployment silently ignored warnings already in force. | Issue every active warning on a genuinely new baseline; migrate V3/V4 baselines without replay. |
| High | Android dropped messages when permission or a channel was temporarily blocked. | Commit events to a local inbox first and replay pending items after access is restored. |
| High | Server retries could create duplicate notifications. | Persist posted event IDs and notify with the event ID as the stable notification tag. |
| Medium | Unknown channel/deep-link values were trusted. | Allow-list notification channels and `weathermetro` destinations. |
| Medium | FCM pending-message deletion was ignored. | Record `onDeletedMessages` and force a full weather refresh on the next app resume. |
| Medium | Users had no direct path to diagnose Android notification controls. | Add a settings tile that opens the app's system notification page. |
| Medium | Replaying many events could repeatedly rewrite the whole inbox on the UI thread. | Batch posted IDs into one durable write and replay from an IO coroutine. |
| Medium | A later warning occurrence with identical wording could reuse an old dedupe ID. | Include the HKO occurrence/update time in the deterministic event ID. |

## Resulting delivery path

1. A one-minute Apps Script trigger retries any existing outbox work before it
   contacts HKO, so an HKO outage cannot block recovery from an earlier FCM
   outage.
2. HKO state is normalised and compared using stable content fingerprints.
3. New issue/update/cancel events enter per-event Script Properties before the
   new per-alert state is stored.
4. FCM receives a high-priority, 24-hour notification + data message with a
   deterministic event ID/tag, journal cursor/URL, and byte-bounded preview.
5. While the app is backgrounded, Google Play services can render the preview
   directly in the system tray. In the foreground, Android validates and posts
   it through the app. Both paths use notification tag/ID `eventId / 0`.
6. App-side receipt or the periodic/foreground safety net asks WorkManager to
   reconcile the complete journal event under that same stable ID.
7. Reconciliation compares every distinct cached/configured journal endpoint,
   selects the newest live cursor, and heals the cached URL after success.
8. A blocked event remains pending. App resume or permission approval rechecks
   global permission and the individual channel, posts eligible events, then
   marks all successful IDs in one commit.
9. A repeated server event is ignored after the same ID is found locally. A
   full-text upgrade updates the existing notification without alerting twice.

## 2026-08-24 real-device incident

The device diagnostics stopped at journal cursor `46 / 46` and reported an HTML
`HTTP 404`, while the production journal had already advanced to cursor `85`.
Cursor `47` was the 2026-08-20 08:40 thunderstorm warning; subsequent rows
included further thunderstorm warnings and storm-related Special Weather Tips.
This proves the backend detector/journal was producing the missing events and
local SWIRLS scheduling was unrelated to the loss.

The regression was introduced when commit `2f624c6` changed schema-v4 FCM from a
visible message into a journal-only wake-up. The Android worker then had a single
cached-URL dependency, so a 404 made the entire official stream silent. The
original app appeared more reliable because its notification payload could be
displayed directly without an additional HTTP fetch.

## 2026-08-25 background-delivery incident

After the endpoint fix, the affected device again proved that the backend and
journal were healthy: opening Weather Metro immediately moved the cursor from
`85 / 85` to `101 / 101` and delivered 16 events. Android notification
permission was enabled and the OEM battery setting was already **unrestricted**.
The foreground-triggered catch-up therefore isolated the remaining failure to
the data-only FCM wake-up while the app process was backgrounded.

FCM also evaluates whether high-priority messages result in visible user
notifications. The earlier silent data-only period could cause per-installation
deprioritisation. A notification payload gives Google Play services a direct,
user-visible delivery path and is eligible for notification delegation without
waiting for `FirebaseMessagingService` to start.

FCM notification messages are collapsible by platform design. Weather Metro
does not treat that transport copy as durable truth: every publication is still
journalled before sending, and WorkManager/app resume recovers every cursor. The
system-tray copy optimises immediate visibility; the journal preserves complete,
ordered delivery.

## Verification

- `node --test backend/apps-script/Code.test.mjs`: 10/10 passed.
- Android notification JUnit tests: 4/4 passed.
- `:app:compileDebugKotlin`: passed using the normal project build.
- `testDebugUnitTest`: passed in the isolated validation run.
- `lintDebug`: passed after fixing permission and API-level findings.
- `assembleDebug`: reached debug signing, then the existing Windows
  `debug.keystore.lock` issue stopped `validateSigningDebug`.

The isolated run manually compiled the generated `BuildConfig.java` and skipped
only Gradle's locked Java compiler task. That temporary build configuration was
removed after testing.

## Deployment and operations

1. Copy the updated `Code.gs` and `Journal.gs` into the production Apps Script
   project. No new Web App URL is required because the public journal API shape
   is unchanged.
2. Confirm `FIREBASE_PROJECT_ID`, `FIREBASE_CLIENT_EMAIL`, and
   `FIREBASE_PRIVATE_KEY` Script Properties are present.
3. Run `sendTestNotification`, then run `installOneMinuteTrigger` once.
4. Monitor Apps Script **Executions** and owner failure emails. Outbox failures
   deliberately fail the due execution after preserving retry state.
5. Release the updated Android client. Existing V3/V4 server state migrates
   automatically; Android's inbox starts empty.
6. On a test device, verify global notification permission and all three
   warning channels through the new system-settings link.

## Remaining limits and next architecture

For a stricter service-level objective, replace topic-only delivery with an
authenticated installation registry and a durable server event journal. Each
client should expose a last-seen cursor, fetch missed events on startup/push,
and acknowledge durable receipt. Add delivery telemetry and an alarm when the
oldest outbox event exceeds a threshold. A redundant scheduler outside Apps
Script would remove the remaining single-trigger dependency.

Official constraints used in this review:

- [FCM topic messaging](https://firebase.google.com/docs/cloud-messaging/topic-messaging)
- [Android message priority](https://firebase.google.com/docs/cloud-messaging/android-message-priority)
- [Collapsible message behavior](https://firebase.google.com/docs/cloud-messaging/customize-messages/collapsible-message-types)
- [FCM message lifetime](https://firebase.google.com/docs/cloud-messaging/customize-messages/setting-message-lifespan)
- [FCM error and payload limits](https://firebase.google.com/docs/cloud-messaging/error-codes)
- [Android notification permission](https://developer.android.com/develop/ui/compose/notifications/notification-permission)
- [Android notification channels](https://developer.android.com/develop/ui/compose/notifications/channels)
- [Apps Script quotas](https://developers.google.com/apps-script/guides/services/quotas)

# Notification reliability review

Reviewed: 2026-08-15

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
| Critical | A transient FCM error followed by an HKO state change could lose the event forever. | Persist each deterministic event to an outbox before advancing alert state; retry with exponential backoff. |
| Critical | Topic payloads may only be 2,048 bytes, but complete warning bodies were embedded. | Bound title/body by UTF-8 bytes and keep ample envelope headroom. |
| Critical | A single outbox or state JSON value could exceed Apps Script's 9 KB property limit. | Store one alert/event per property with a separate bounded index. |
| High | `collapse_key` could replace an older undelivered issue/update/cancel event. | Make alert events non-collapsible. |
| High | A one-hour TTL discarded warnings after a moderately long offline period. | Extend TTL to 24 hours and reconcile current weather when FCM reports deleted pending messages. |
| High | Normal-priority alerts could be delayed during Android Doze. | Use high priority for every user-visible warning/tip; keep only service-status tests normal. |
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
4. FCM receives a non-collapsible, high-priority, 24-hour data message with a
   deterministic event ID and byte-bounded content.
5. Android validates the message and synchronously commits it to the local
   inbox before attempting the system notification.
6. A blocked event remains pending. App resume or permission approval rechecks
   global permission and the individual channel, posts eligible events, then
   marks all successful IDs in one commit.
7. A repeated server event is ignored after the same ID is found locally.

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

1. Deploy the updated `backend/apps-script/Code.gs` and manifest.
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

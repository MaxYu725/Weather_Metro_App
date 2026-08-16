# Notification pipeline health — Phase 2C2

Reviewed: 2026-08-16

## Goal

Phase 2B made delivery recoverable after the backend detects an HKO publication.
Phase 2C1 added an independent official HKO RSS cross-check. Phase 2C2 adds
operational proof that the detector, journal, FCM outbox and source cross-check
continue to run rather than silently failing.

## Health model

`PipelineHealth.gs` records compact Script Properties only. It never stores HKO
publication bodies or Firebase credentials.

The runtime state records:

- last journal execution attempt
- last successful HKO `warnsum` / `warningInfo` / `swt` poll
- last journal check and journal append
- latest observed journal cursor
- last outbox flush
- pending outbox count and last flush failure count
- last completed run
- last failure and bounded error text

The health snapshot additionally combines:

- journal trigger count
- RSS cross-check trigger count
- oldest durable outbox event age
- latest RSS/JSON parity status
- persistent `SECONDARY_ONLY` streak

## Status policy

Action-required states are:

- `JOURNAL_TRIGGER_INVALID`
- `SOURCE_TRIGGER_INVALID`
- `POLL_UNPROVEN`
- `POLL_STALE` — no successful full HKO poll for more than 3 minutes
- `OUTBOX_STALLED` — a durable FCM outbox event remains queued for more than 5 minutes
- `SOURCE_CROSSCHECK_STALE` — the independent source check is older than 4 minutes
- `SOURCE_GAP_CONFIRMED` — the same RSS-only warning remains absent from JSON for at least two cross-check cycles

Transient states such as one source mismatch, one source error, or a recent run
failure are exposed as degraded health but do not immediately trigger the external
scheduled monitor.

## Source-gap re-read policy

The HKO RSS cross-check now runs every minute. If RSS contains an active warning
that is missing from the initial JSON summary, Weather Metro performs up to two
fresh JSON re-reads, separated by 1.5 seconds and with a cache-busting query value.

If the JSON source catches up, the result becomes `MATCH_AFTER_RETRY` and the
persistent mismatch streak resets. If the re-read itself fails, the original
RSS-only gap is retained; retry failure must never erase evidence of a possible
primary-source miss.

RSS still does **not** synthesize or cancel a user-visible weather warning in this
checkpoint. A second summary feed can prove that the primary detector may have a
gap, but it does not carry the same full detailed publication/action contract as
`warnsum + warningInfo + swt`. Auto-failover requires a separate, fail-closed
publication mapping checkpoint.

## Public health endpoint

The existing Apps Script Web App gains a read-only health mode:

`GET <journalUrl>?mode=health`

It returns `generatedAtEpochMs` plus the compact pipeline health object. It does
not expose Script Properties, Firebase credentials, HKO feed bodies, or the
private Google Sheet ID.

Because Apps Script `/exec` uses a deployed version, changing `Journal.gs` or
adding `PipelineHealth.gs` requires updating the existing Web App deployment to a
new version before `?mode=health` becomes available in production.

## External watchdog

`.github/workflows/notification-health.yml` probes the production health endpoint
every 15 minutes and can also be run manually. It fails only when
`health.actionRequired` is true; transient degraded states are emitted as a
workflow warning rather than creating noisy failures.

This watchdog is deliberately outside Apps Script. If the Apps Script Web App is
unreachable, the external probe can still detect that failure.

## Production activation

Before merging this checkpoint:

1. Copy the updated `Journal.gs` and `SourceRedundancy.gs` to the existing Weather
   Metro Apps Script project.
2. Add `PipelineHealth.gs`.
3. Run `setupReliableNotifications()` to confirm exactly one one-minute journal
   trigger.
4. Run `setupWarningSourceRedundancy()` again; Phase 2C2 changes it from five
   minutes to one minute.
5. Wait for at least one successful journal poll.
6. Run `verifyNotificationPipelineHealth()`. Expected steady state is `HEALTHY`.
7. Edit the existing Apps Script Web App deployment and select **New version**.
   Keep execute-as/access settings unchanged.
8. Open `<journalUrl>?mode=health` in an incognito window and confirm a JSON
   `health` object is returned without Google sign-in.

The journal `/exec` URL itself should remain unchanged.

## Boundary

Phase 2C2 detects persistent detector disagreement and stale delivery components;
it still does not claim that Android can force an OS notification while the app
is force-stopped, offline indefinitely, or notifications are blocked.

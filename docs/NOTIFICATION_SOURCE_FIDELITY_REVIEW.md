# Notification source-fidelity review

Reviewed: 2026-08-16

## Goal

Weather Metro should treat Hong Kong Observatory publications as the source of
truth: if HKO publishes a warning action, detailed warning statement, or Special
Weather Tip, Weather Metro should preserve that publication instead of inferring
a different event from the before/after warning state.

This is separate from transport reliability. The previous notification phase
made FCM delivery retryable and made Android receipt durable. This review found
that the source-normalisation layer could still change or omit HKO semantics even
when transport worked perfectly.

## Findings

| Severity | Finding | Previous behaviour | Required behaviour |
| --- | --- | --- | --- |
| Critical | Official warning actions were not preserved | `warnsum.actionCode` was only consulted to skip `CANCEL`; the app reconstructed ISSUE/UPDATE/CANCEL from state presence and a content fingerprint | Preserve the HKO action code, including ISSUE, REISSUE, CANCEL, EXTEND and UPDATE |
| Critical | A same-text publication could be missed | Fingerprints excluded HKO action and timestamps, so an official reissue/extension with unchanged wording could look unchanged | Publication identity includes action plus official timestamps and content |
| High | Cancellation could be fabricated from disappearance | Any warning missing from the next snapshot generated a synthetic CANCEL using the previous state | Emit cancellation only when HKO publishes a cancellation action/statement |
| High | `warningInfo`-only statements could be dropped | Detailed rows were only used as enrichment for a `warnsum` row | Preserve unmatched official warningInfo statements, including WTCPRE8 |
| High | Special Weather Tips could generate fake cancellations | A changed/disappeared body was treated as state removal | Treat each SWT item as a publication; disappearance is not an HKO cancellation |
| Medium | HKO formatting was altered | All whitespace was collapsed to spaces | Keep source line breaks and only normalise line endings/trailing horizontal whitespace |
| Medium | Notification title added Weather Metro semantics | The backend prefixed titles with `已發出`, `已更新`, or `已取消` | Use the HKO-derived title directly; keep action as structured metadata |
| High | FCM is still not a complete source record | Visible body is limited to the safe FCM payload excerpt and there is no server cursor | Add a durable full-text event journal and client reconciliation in the next checkpoint |

## Phase 2A implementation

The Apps Script baseline moves to a V6 source-publication model.

A publication ID is derived from:

- source type (`WARNING`, `STATEMENT`, `SWT`)
- stable source key / warning family
- warning code
- HKO action code
- issue / expiry / update time
- title
- source body

`diffStates_` now only emits publications present in the new HKO snapshot whose
publication ID was not in the previous snapshot. It no longer converts a missing
item into a cancellation.

For warning summary rows, the HKO action is the event kind. For unmatched
`warningInfo` rows the event kind is `STATEMENT`. For Special Weather Tips the
event kind is `SWT`.

The FCM schema moves to version 3 and carries `sourceType` and `sourceTime` in
addition to the existing event metadata. Android can ignore these extra values
until the journal/cursor client is introduced.

## Verification added

Backend tests now cover:

- official REISSUE being preserved on first observation
- EXTEND being emitted even when the warning text is unchanged
- explicit HKO cancellation text
- no synthetic cancellation when a snapshot item disappears
- WTCPRE8 as a warningInfo-only statement
- no fake cancellation for disappearing SWT content
- preservation of source line breaks
- HKO title without Weather Metro-generated action prefixes
- deterministic source-publication IDs and existing durable outbox behaviour
- Script Properties per-value size limits and corrupt-index failure handling

## Remaining work before claiming end-to-end eventual delivery

### Phase 2B — durable publication journal

FCM must stop being the only path by which an Android installation can learn an
event exists. Apps Script (or a more suitable backend store) should append each
source publication to a durable, cursor-addressable journal before the FCM send.
The journal must retain the complete HKO body, not the 900-byte FCM preview.

Android should reconcile the journal:

- on application startup/resume
- immediately after an FCM wake-up
- after `onDeletedMessages`
- after notification permission/channel access is restored

A local last-seen cursor plus the existing event-ID inbox makes a missed FCM push
recoverable instead of permanent.

### Phase 2C — receipt proof and redundant detection

For a measurable delivery objective, register installations and record an ACK for
journal events after durable client receipt. Add operational alarms for stale
unacknowledged critical events and the oldest unsent outbox entry.

A second independent HKO detection path should also be evaluated. HKO publishes
official Weather Warning Summary and Weather Warning Information RSS feeds "as
necessary"; these can be used as a cross-check/redundant source for the JSON open
data endpoints rather than as a second user-visible notification stream.

## External constraints

HKO documents its mobile-app notification service as using FCM and explicitly
states that successful or timely reception cannot be guaranteed. Weather Metro
therefore cannot honestly guarantee that Android will display a system
notification while a device is force-stopped, offline indefinitely, or blocked
by OS/user notification controls. The engineering target is instead:

> Every HKO publication detected by the backend is durably journaled, and every
> enabled installation eventually receives and displays each journal event once
> when it next has network/execution/notification capability.

That target is testable and can recover from missed push delivery.

## References

- HKO Open Data API documentation (Weather Warning Summary / Warning Information / Special Weather Tips)
- HKO RSS Weather Information: Weather Warning Summary and Weather Warning Information
- HKO MyObservatory notes on FCM notification delivery limitations

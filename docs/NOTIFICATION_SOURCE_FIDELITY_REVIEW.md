# Notification source-fidelity and recovery review

Reviewed: 2026-08-16

## Goal

Weather Metro should treat Hong Kong Observatory publications as the source of
truth. If HKO publishes a warning action, detailed warning statement, or Special
Weather Tip, Weather Metro should preserve that publication rather than infer a
different event from before/after state. A missed FCM delivery must also be
recoverable instead of becoming a permanent notification gap.

## Findings and disposition

| Severity | Finding | Previous behaviour | Current disposition |
| --- | --- | --- | --- |
| Critical | Official warning actions were not preserved | `warnsum.actionCode` was mostly discarded and ISSUE/UPDATE/CANCEL were reconstructed locally | Fixed: preserve ISSUE, REISSUE, CANCEL, EXTEND, UPDATE and future source action strings |
| Critical | A same-text publication could be missed | Action/timestamps were absent from identity | Fixed: action, official timestamps and content are part of publication identity |
| High | Cancellation could be fabricated | Snapshot disappearance generated a synthetic CANCEL | Fixed: cancellation is emitted only from HKO cancellation semantics |
| High | `warningInfo`-only statements could be dropped | Detail rows only enriched `warnsum` | Fixed: unmatched official statements such as WTCPRE8 are preserved |
| High | SWT disappearance could create a fake cancellation | Tips were treated like active state | Fixed: SWT is a source publication; disappearance is not cancellation |
| Medium | Source formatting/title was altered | Whitespace collapsed and local action prefixes were added | Fixed: retain source line breaks and HKO-derived title |
| Critical | FCM was the only discovery path | A push lost by FCM/Android could never be recovered | Fixed in Phase 2B: authoritative cursor journal + WorkManager reconciliation |
| High | FCM preview could truncate official body | Payload had a safe ~900-byte body limit | Fixed for journal-capable clients: preview wakes the client; complete journal event is displayed |
| High | Local inbox writes were not verified | `SharedPreferences.commit()` result was ignored | Fixed: durable write failure throws and cursor cannot advance |
| Medium | Journal migration could repost existing complete notifications | New metadata can differ even when visible content is identical | Fixed: metadata-only upgrade preserves posted state; changed visible content is reposted |

## Phase 2A — source publication fidelity

The source-publication model identifies an HKO publication from:

- source type (`WARNING`, `STATEMENT`, `SWT`)
- stable source key / warning family
- warning code
- HKO action code
- issue / expiry / update time
- title
- source body

Only newly observed publication IDs become events. Missing snapshot rows do not
become cancellations. `warningInfo` statements without a matching summary row are
retained. WTCPRE8 uses the HKO wording `預警八號熱帶氣旋警告信號特別報告`.

## Phase 2B — durable full-text journal and client recovery

The authoritative flow is now:

`HKO JSON -> publication ID -> Google Sheets journal -> FCM wake-up -> WorkManager -> local inbox -> system notification`

The complete event is journalled before FCM is queued. FCM schema v4 carries the
journal URL/cursor and a byte-bounded compatibility preview. A journal-capable
Android client fetches the complete ordered journal instead of displaying the
preview directly.

Android reconciles:

- on application startup
- on application resume
- immediately after a schema v4 FCM wake-up
- after `onDeletedMessages`
- periodically as a network-connected safety net
- when notification permission is enabled/restored

Journal pages are parsed fail-closed. A malformed event is not skipped. The local
cursor advances only after the corresponding complete event has been committed
to the durable inbox. If a previously displayed preview is later replaced by
complete source content, it is updated/reposted; a metadata-only migration does
not generate a duplicate visible notification.

The journal uses Google Sheets because complete historical event bodies do not
fit the bounded Apps Script property store. Script Properties continue to hold
small state/outbox metadata and Firebase credentials only.

## Verification

Backend tests cover:

- REISSUE / EXTEND with unchanged text
- explicit cancellation and no synthetic cancellation
- WTCPRE8 and SWT source behaviour
- source line-break preservation
- deterministic event IDs
- full journal body versus bounded FCM preview
- ordered cursor paging with no aggregation
- journal/outbox retry and corruption handling
- Apps Script property-size safety

Android tests cover:

- source/journal metadata parsing
- unsafe routing rejection
- full journal body preservation beyond FCM preview size
- strict increasing cursors
- production Apps Script endpoint validation
- local inbox round-trip/deduplication
- preview-to-full-content upgrade
- metadata-only migration without duplicate repost
- posted-history bounding without deleting pending events

CI runs all Apps Script tests plus Android unit tests, lint, and debug assembly.

## Phase 2C — remaining reliability work

Phase 2B recovers **delivery-path loss** after the backend has detected an HKO
publication. It does not yet make backend detection itself redundant. The next
checkpoint should therefore focus on:

1. **Independent official-source cross-check.** Evaluate HKO Weather Warning RSS
   as a second detector for the JSON open-data stream. Canonicalise both detectors
   into the same publication ID so redundancy cannot create duplicate user alerts.
2. **Operational proof.** Record backend health such as oldest unsent outbox event,
   last successful HKO poll, journal append failures, and cursor/API health.
3. **Optional installation ACK.** If measurable per-installation receipt proof is
   required, register installations and acknowledge events only after durable
   local receipt. Topic-FCM acceptance alone cannot provide per-device proof.
4. **Personalised weather stream.** Location-specific heavy-rain, rain and
   lightning notifications should use Weather Metro's existing location/Rain
   ownership rather than be mixed into this territory-wide publication journal.

## External constraints and engineering target

No Android app can force the operating system to display a notification while an
app is force-stopped, a device remains offline indefinitely, or the user/OS has
blocked notification permission/channel access. The enforceable target is:

> Every HKO publication detected by the backend is durably journalled before
> delivery is attempted. Every enabled installation eventually retrieves every
> journal event once network/execution capability returns, and events that cannot
> currently be posted remain in the local inbox for replay.

This makes a missed push recoverable and separates backend detection, transport,
durable client receipt, and visible OS posting into independently testable stages.

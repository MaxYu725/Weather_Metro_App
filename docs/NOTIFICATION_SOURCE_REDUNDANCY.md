# Notification Phase 2C1 — HKO source redundancy shadow monitor

Reviewed: 2026-08-16

## Purpose

Phase 2B made delivery recoverable **after** the backend detects an HKO
publication. Phase 2C1 starts validating backend-detection redundancy without
changing production notification semantics.

The production one-minute journal continues to use the HKO JSON open-data
sources. A separate low-frequency shadow monitor compares the official HKO JSON
weather-warning summary with the separately hosted official HKO RSS weather-
warning summary.

Official secondary source:

`https://rss.weather.gov.hk/rss/WeatherWarningSummaryv2_uc.xml`

DATA.GOV.HK describes this Traditional Chinese RSS resource as the Hong Kong
Observatory weather warning summary, updated as and when there is an update.

## Why shadow mode first

The RSS feed is a different publication path and format. It is useful as an
independent detector, but immediately treating an RSS/JSON mismatch as a user
alert could create duplicate or stale warning notifications when the two official
feeds update a few seconds apart.

For this checkpoint the secondary source therefore **cannot** create, suppress,
or modify a user-visible Weather Metro notification. It records compact health
only. This lets us observe real production parity before enabling any failover
policy.

## Canonical comparison

The monitor does not compare raw XML/JSON bytes. Both sources are reduced to a
stable set of active-warning tokens, for example:

- `TC:1`, `TC:3`, `TC:8`, `TC:9`, `TC:10`
- `RAIN:AMBER`, `RAIN:RED`, `RAIN:BLACK`
- `THUNDERSTORM`
- `HOT`, `COLD`, `FROST`
- `FIRE`, `LANDSLIP`, `NT_FLOOD`, `MONSOON`, `TSUNAMI`

JSON rows whose official `actionCode` is `CANCEL` are excluded from the active
warning set. This avoids comparing a cancellation publication with an RSS feed
whose documented purpose is warnings currently in force.

The compact result is stored in Script Properties under:

`HKO_WARNING_SOURCE_CROSSCHECK_HEALTH_V1`

No RSS body is stored there. The record contains only status, warning tokens,
small error strings, a digest, and mismatch streak metadata.

## Status meanings

- `MATCH`: both official paths expose the same recognised active warnings.
- `PRIMARY_ONLY`: JSON exposes recognised warnings not visible in RSS. This may
  be normal short-lived RSS lag.
- `SECONDARY_ONLY`: RSS exposes recognised warnings not visible in JSON. This is
  the most important candidate backend-detection gap.
- `DIVERGED`: both paths contain recognised warnings absent from the other.
- `PRIMARY_ERROR`, `SECONDARY_ERROR`, `BOTH_ERROR`, `CHECK_ERROR`: source or
  execution failure. The production journal is not blocked by a shadow error.

Repeated identical `SECONDARY_ONLY`/`DIVERGED` observations increment
`consecutiveSecondaryOnly`. Phase 2C2 can use observed production behaviour to
choose a safe fail-closed or failover threshold rather than guessing one now.

## Deployment

Copy `backend/apps-script/SourceRedundancy.gs` into the existing Weather Metro
Apps Script project. No new OAuth scope is required beyond the existing external
request scope.

Run once from the Apps Script editor:

`setupWarningSourceRedundancy()`

This replaces only its own previous shadow trigger and installs one five-minute
`checkWarningSourceRedundancy` trigger. It does **not** touch the production
one-minute `checkWeatherUpdatesJournalled` trigger.

Then run:

`verifyWarningSourceRedundancy()`

Expected setup state:

- `triggerCount = 1`
- `health` is non-null after the immediate first check
- normal steady-state status is `MATCH`

A temporary `PRIMARY_ONLY` or `SECONDARY_ONLY` around an HKO publication update
is not automatically a defect; the mismatch streak and real timestamps are what
will determine Phase 2C2 enforcement.

## Tests

`SourceRedundancy.test.mjs` covers:

- RSS CDATA/HTML/entity normalisation
- matching warning sets
- RSS-only detection
- official JSON cancellation exclusion
- fail-soft secondary-source outage handling
- persistent mismatch streak tracking
- rainstorm/tropical-cyclone level canonicalisation

The existing GitHub Actions glob `backend/apps-script/*.test.mjs` automatically
runs these tests.

## Next checkpoint

After the shadow monitor has been deployed and observed against real HKO updates,
Phase 2C2 should add operational health/proof and decide the enforcement policy:

1. persistent RSS-only detection can force a primary re-read/fail-closed path;
2. source-health timestamps and stale-pipeline thresholds become explicit;
3. only after duplicate/staleness behaviour is proven should RSS be allowed to
   synthesize a recovery publication when the JSON path remains unavailable.

This sequencing preserves the project rule: reliability improvement must not
trade a rare missed event for routine duplicate or fabricated weather alerts.

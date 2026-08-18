# Phase 3B — Current home density polish

## Goal
Keep Phase 3 integrations useful on a phone-sized Current page without repeating detailed observation data that already exists in the expandable current-weather card.

## Phone feedback refinement
- Keep active warning cards compact and full-width when only one warning is active.
- Keep the two-hour rain timeline with real Hong Kong clock times and proportional rain bars.
- Treat the conditions block as a glance strip only; detailed wind / UV / visibility / pressure / rainfall observations remain in the expandable current-weather card.
- Compress Current-page section labels (`alerts & tips`, `next 2 hours`, `live weather`, `conditions`) from the shared large rhythm to a smaller home-only rhythm.
- Compress the conditions glance strip from 72dp to 48dp; do not expand its information scope.

## Rationale
The expanded current-weather card is the canonical detailed observation surface. The lower conditions row remains only as a one-glance summary for users who do not open that card. It should therefore occupy substantially less vertical space than forecast, rain, or warning content.

## Boundaries
- No additional data source.
- No additional refresh or background work.
- No changes to Rain Track or Storm Track ownership.
- No changes to warning notification delivery.

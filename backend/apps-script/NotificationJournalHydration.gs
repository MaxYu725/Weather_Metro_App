/**
 * Quota-optimized full journal hydration for the notification supervisor.
 *
 * The supervisor already fetched the current HKO warnsum snapshot on its
 * one-minute fast path. Reuse that exact snapshot and fetch only warningInfo +
 * SWT here. This preserves the durable journal/outbox ordering while avoiding a
 * duplicate warnsum request and unnecessary steady-state property/health work.
 */
function checkWeatherUpdatesJournalledFromSummary_(summary) {
  if (!summary || typeof summary !== 'object' || Array.isArray(summary)) {
    throw new Error('Missing prefetched HKO warnsum snapshot for journal hydration.');
  }

  const cycleStartedAtEpochMs = Date.now();
  const lock = LockService.getScriptLock();
  if (!lock.tryLock(20000)) {
    console.log('A previous journal hydration is still running; this execution was skipped.');
    return {
      skipped: true,
      journalled: 0,
      sent: 0,
      pending: null,
      failed: 0,
    };
  }

  try {
    assertConfiguration_();
    const properties = PropertiesService.getScriptProperties();

    // Retry already-durable FCM work before source hydration, exactly as the
    // canonical journal owner does.
    const retryResult = flushJournalOutbox_(properties, Date.now());

    const responses = UrlFetchApp.fetchAll([
      hkoRequest_('warningInfo'),
      hkoRequest_('swt'),
    ]);
    const detailPayload = parseHkoResponse_(responses[0]);
    const tipPayload = parseHkoResponse_(responses[1]);

    const publications = normaliseJournalPublications_(summary, detailPayload, tipPayload);
    const currentState = journalStateForPublications_(publications);
    const previousState = readJournalState_(properties);
    const newPublications = previousState === null
      ? publications
      : publications.filter(function (publication) { return !previousState[publication.id]; });
    const stateChanged = previousState === null ||
      !notificationJournalStatesEqual_(previousState, currentState);

    // Spreadsheet access is avoided completely when there is no new event.
    const journalEvents = newPublications.length > 0
      ? ensureJournalEvents_(properties, newPublications, Date.now())
      : [];

    let finalResult = {
      sent: 0,
      failed: 0,
      pending: retryResult.pending,
    };

    if (journalEvents.length > 0) {
      const outbox = enqueueJournalEvents_(readOutbox_(properties), journalEvents, Date.now());

      // Durable outbox first, source state second. A crash between these writes
      // therefore retries by deterministic eventId rather than losing an event.
      writeOutbox_(properties, outbox);
      if (stateChanged) writeJournalState_(properties, currentState);
      finalResult = flushJournalOutbox_(properties, Date.now());
    } else if (stateChanged) {
      // Preserve current-snapshot semantics (including disappearance) without
      // rewriting the same Script Properties on every routine hydration.
      writeJournalState_(properties, currentState);
    }

    const sent = retryResult.sent + finalResult.sent;
    const failed = retryResult.failed + finalResult.failed;
    const completedAtEpochMs = Date.now();
    notificationJournalHydrationMarkSuccess_(
      properties,
      cycleStartedAtEpochMs,
      completedAtEpochMs,
      journalEvents,
      finalResult.pending,
      failed,
    );

    console.log(
      'Hydrated journal from prefetched warnsum: ' + journalEvents.length +
      ' new publication(s); sent ' + sent + ', pending ' + finalResult.pending + '.',
    );
    if (failed > 0) {
      throw new Error(failed + ' queued FCM wake-up attempt(s) failed and will be retried.');
    }

    return {
      skipped: false,
      journalled: journalEvents.length,
      sent: sent,
      pending: finalResult.pending,
      failed: failed,
      stateChanged: stateChanged,
    };
  } catch (error) {
    try {
      const properties = PropertiesService.getScriptProperties();
      if (typeof notificationPipelineMarkFailure_ === 'function') {
        notificationPipelineMarkFailure_(properties, Date.now(), error);
      }
      // Do not recompute the full health snapshot here. The supervisor refreshes
      // health on component failure; verify/doGet derive healthy state live.
    } catch (healthError) {
      console.error(
        'Notification hydration failure recording failed: ' +
        String(healthError && healthError.message ? healthError.message : healthError),
      );
    }
    throw error;
  } finally {
    lock.releaseLock();
  }
}

/**
 * Healthy hydration previously performed four independent read/modify/write
 * cycles against Script Properties. On the one-minute supervisor that service
 * overhead materially contributes to the daily trigger-runtime quota. When the
 * compact pipeline runtime owner is available, update the same metadata with a
 * single read and a single write. Tests/legacy deployments retain the old helper
 * fallback so this optimisation cannot weaken health semantics during rollout.
 */
function notificationJournalHydrationMarkSuccess_(
  properties,
  attemptedAtEpochMs,
  completedAtEpochMs,
  journalEvents,
  pendingCount,
  failedCount,
) {
  const events = Array.isArray(journalEvents) ? journalEvents : [];
  const completedAt = Number(completedAtEpochMs || Date.now());
  const attemptedAt = Number(attemptedAtEpochMs || completedAt);
  const failed = Math.max(0, Number(failedCount || 0));

  if (
    typeof readNotificationPipelineRuntime_ === 'function' &&
    typeof writeNotificationPipelineRuntime_ === 'function'
  ) {
    const runtime = readNotificationPipelineRuntime_(properties);
    runtime.lastAttemptEpochMs = attemptedAt;
    runtime.lastHkoPollSuccessEpochMs = completedAt;
    runtime.lastJournalCheckEpochMs = completedAt;
    if (events.length > 0) {
      runtime.lastJournalAppendEpochMs = completedAt;
      const cursor = Number(events[events.length - 1].journalCursor || 0);
      if (Number.isFinite(cursor) && cursor > 0) runtime.latestJournalCursor = cursor;
    }
    runtime.lastOutboxFlushEpochMs = completedAt;
    runtime.pendingOutboxEvents = Math.max(0, Number(pendingCount || 0));
    runtime.lastFlushFailedEvents = failed;
    if (failed === 0) {
      runtime.lastCompletedEpochMs = completedAt;
      runtime.lastError = '';
    }
    writeNotificationPipelineRuntime_(properties, runtime);
    return;
  }

  if (typeof notificationPipelineMarkAttempt_ === 'function') {
    notificationPipelineMarkAttempt_(properties, attemptedAt);
  }
  if (typeof notificationPipelineMarkSourceSuccess_ === 'function') {
    notificationPipelineMarkSourceSuccess_(properties, completedAt);
  }
  if (typeof notificationPipelineMarkJournalCommit_ === 'function') {
    notificationPipelineMarkJournalCommit_(properties, completedAt, events);
  }
  if (typeof notificationPipelineMarkFlush_ === 'function') {
    notificationPipelineMarkFlush_(properties, completedAt, pendingCount, failed);
  }
}

function notificationJournalStatesEqual_(left, right) {
  if (!left || !right) return false;
  const leftIds = Object.keys(left).sort();
  const rightIds = Object.keys(right).sort();
  if (leftIds.length !== rightIds.length) return false;
  for (let index = 0; index < leftIds.length; index += 1) {
    if (leftIds[index] !== rightIds[index]) return false;
    const id = leftIds[index];
    if (JSON.stringify(left[id]) !== JSON.stringify(right[id])) return false;
  }
  return true;
}

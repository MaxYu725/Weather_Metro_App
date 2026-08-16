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
    if (typeof notificationPipelineMarkAttempt_ === 'function') {
      notificationPipelineMarkAttempt_(properties, Date.now());
    }

    // Retry already-durable FCM work before source hydration, exactly as the
    // canonical journal owner does.
    const retryResult = flushJournalOutbox_(properties, Date.now());

    const responses = UrlFetchApp.fetchAll([
      hkoRequest_('warningInfo'),
      hkoRequest_('swt'),
    ]);
    const detailPayload = parseHkoResponse_(responses[0]);
    const tipPayload = parseHkoResponse_(responses[1]);
    if (typeof notificationPipelineMarkSourceSuccess_ === 'function') {
      notificationPipelineMarkSourceSuccess_(properties, Date.now());
    }

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
    if (typeof notificationPipelineMarkJournalCommit_ === 'function') {
      notificationPipelineMarkJournalCommit_(properties, Date.now(), journalEvents);
    }

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
    if (typeof notificationPipelineMarkFlush_ === 'function') {
      notificationPipelineMarkFlush_(properties, Date.now(), finalResult.pending, failed);
    }

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

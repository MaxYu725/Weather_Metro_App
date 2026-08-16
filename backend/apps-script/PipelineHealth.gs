/**
 * Weather Metro notification pipeline health model.
 *
 * This file records only compact operational metadata. It never stores HKO
 * publication bodies or Firebase credentials. Journal delivery semantics remain
 * owned by Journal.gs; source parity remains owned by SourceRedundancy.gs.
 */

const PIPELINE_HEALTH_CONFIG = Object.freeze({
  runtimePropertyKey: 'HKO_NOTIFICATION_PIPELINE_RUNTIME_V1',
  snapshotPropertyKey: 'HKO_NOTIFICATION_PIPELINE_HEALTH_V1',
  schemaVersion: 1,
  maxPollAgeMs: 3 * 60 * 1000,
  maxSourceCheckAgeMs: 4 * 60 * 1000,
  maxOutboxAgeMs: 5 * 60 * 1000,
  confirmedSourceGapStreak: 2,
});

function notificationPipelineMarkAttempt_(properties, nowEpochMs) {
  const state = readNotificationPipelineRuntime_(properties);
  state.lastAttemptEpochMs = Number(nowEpochMs || Date.now());
  writeNotificationPipelineRuntime_(properties, state);
}

function notificationPipelineMarkSourceSuccess_(properties, nowEpochMs) {
  const state = readNotificationPipelineRuntime_(properties);
  state.lastHkoPollSuccessEpochMs = Number(nowEpochMs || Date.now());
  writeNotificationPipelineRuntime_(properties, state);
}

function notificationPipelineMarkJournalCommit_(properties, nowEpochMs, journalEvents) {
  const state = readNotificationPipelineRuntime_(properties);
  const now = Number(nowEpochMs || Date.now());
  const events = Array.isArray(journalEvents) ? journalEvents : [];
  state.lastJournalCheckEpochMs = now;
  if (events.length > 0) {
    state.lastJournalAppendEpochMs = now;
    const cursor = Number(events[events.length - 1].journalCursor || 0);
    if (Number.isFinite(cursor) && cursor > 0) state.latestJournalCursor = cursor;
  }
  writeNotificationPipelineRuntime_(properties, state);
}

function notificationPipelineMarkFlush_(properties, nowEpochMs, pendingCount, failedCount) {
  const state = readNotificationPipelineRuntime_(properties);
  const now = Number(nowEpochMs || Date.now());
  state.lastOutboxFlushEpochMs = now;
  state.pendingOutboxEvents = Math.max(0, Number(pendingCount || 0));
  state.lastFlushFailedEvents = Math.max(0, Number(failedCount || 0));
  if (state.lastFlushFailedEvents === 0) {
    state.lastCompletedEpochMs = now;
    state.lastError = '';
  }
  writeNotificationPipelineRuntime_(properties, state);
}

function notificationPipelineMarkFailure_(properties, nowEpochMs, error) {
  const state = readNotificationPipelineRuntime_(properties);
  state.lastFailureEpochMs = Number(nowEpochMs || Date.now());
  state.lastError = String(error && error.message ? error.message : error || 'Unknown pipeline failure')
    .slice(0, 500);
  writeNotificationPipelineRuntime_(properties, state);
}

function readNotificationPipelineRuntime_(properties) {
  const raw = properties.getProperty(PIPELINE_HEALTH_CONFIG.runtimePropertyKey);
  if (!raw) {
    return {
      schemaVersion: PIPELINE_HEALTH_CONFIG.schemaVersion,
      lastAttemptEpochMs: 0,
      lastHkoPollSuccessEpochMs: 0,
      lastJournalCheckEpochMs: 0,
      lastJournalAppendEpochMs: 0,
      lastOutboxFlushEpochMs: 0,
      lastCompletedEpochMs: 0,
      lastFailureEpochMs: 0,
      latestJournalCursor: 0,
      pendingOutboxEvents: 0,
      lastFlushFailedEvents: 0,
      lastError: '',
    };
  }
  try {
    const parsed = JSON.parse(raw);
    if (!parsed || typeof parsed !== 'object') throw new Error('not an object');
    return Object.assign({ schemaVersion: PIPELINE_HEALTH_CONFIG.schemaVersion }, parsed);
  } catch (_error) {
    return {
      schemaVersion: PIPELINE_HEALTH_CONFIG.schemaVersion,
      lastAttemptEpochMs: 0,
      lastHkoPollSuccessEpochMs: 0,
      lastJournalCheckEpochMs: 0,
      lastJournalAppendEpochMs: 0,
      lastOutboxFlushEpochMs: 0,
      lastCompletedEpochMs: 0,
      lastFailureEpochMs: 0,
      latestJournalCursor: 0,
      pendingOutboxEvents: 0,
      lastFlushFailedEvents: 0,
      lastError: 'Invalid pipeline runtime state was ignored.',
    };
  }
}

function writeNotificationPipelineRuntime_(properties, state) {
  properties.setProperty(
    PIPELINE_HEALTH_CONFIG.runtimePropertyKey,
    JSON.stringify(Object.assign({ schemaVersion: PIPELINE_HEALTH_CONFIG.schemaVersion }, state)),
  );
}

function refreshNotificationPipelineHealth_(properties, nowEpochMs) {
  const now = Number(nowEpochMs || Date.now());
  const runtime = readNotificationPipelineRuntime_(properties);
  const outbox = typeof readOutbox_ === 'function' ? readOutbox_(properties) : [];
  const sourceHealth = typeof readWarningSourceCrossCheck_ === 'function'
    ? readWarningSourceCrossCheck_(properties)
    : null;
  const triggers = typeof ScriptApp !== 'undefined' && ScriptApp.getProjectTriggers
    ? ScriptApp.getProjectTriggers()
    : [];
  const journalTriggerCount = triggers.filter(function (trigger) {
    return trigger.getHandlerFunction() === JOURNAL_CONFIG.triggerFunction;
  }).length;
  const sourceTriggerCount = triggers.filter(function (trigger) {
    return typeof SOURCE_REDUNDANCY_CONFIG !== 'undefined' &&
      trigger.getHandlerFunction() === SOURCE_REDUNDANCY_CONFIG.triggerFunction;
  }).length;
  const oldestQueuedAt = outbox.reduce(function (oldest, entry) {
    const queuedAt = Number(entry && entry.queuedAtEpochMs || 0);
    if (!Number.isFinite(queuedAt) || queuedAt <= 0) return oldest;
    return oldest === 0 ? queuedAt : Math.min(oldest, queuedAt);
  }, 0);

  const facts = {
    nowEpochMs: now,
    runtime: runtime,
    pendingOutboxEvents: outbox.length,
    oldestOutboxQueuedAtEpochMs: oldestQueuedAt,
    journalTriggerCount: journalTriggerCount,
    sourceTriggerCount: sourceTriggerCount,
    sourceHealth: sourceHealth,
  };
  const snapshot = deriveNotificationPipelineHealth_(facts);
  properties.setProperty(PIPELINE_HEALTH_CONFIG.snapshotPropertyKey, JSON.stringify(snapshot));
  return snapshot;
}

function deriveNotificationPipelineHealth_(facts) {
  const now = Number(facts.nowEpochMs || Date.now());
  const runtime = facts.runtime || {};
  const source = facts.sourceHealth || null;
  const lastPoll = Number(runtime.lastHkoPollSuccessEpochMs || 0);
  const sourceCheckedAt = Number(source && source.checkedAtEpochMs || 0);
  const oldestQueuedAt = Number(facts.oldestOutboxQueuedAtEpochMs || 0);
  const pollAgeMs = lastPoll > 0 ? Math.max(0, now - lastPoll) : null;
  const sourceCheckAgeMs = sourceCheckedAt > 0 ? Math.max(0, now - sourceCheckedAt) : null;
  const oldestOutboxAgeMs = oldestQueuedAt > 0 ? Math.max(0, now - oldestQueuedAt) : 0;
  const pendingOutboxEvents = Math.max(0, Number(facts.pendingOutboxEvents || 0));
  const journalTriggerCount = Number(facts.journalTriggerCount || 0);
  const sourceTriggerCount = Number(facts.sourceTriggerCount || 0);
  const sourceStatus = source && source.status ? String(source.status) : 'UNAVAILABLE';
  const sourceGapStreak = Number(source && source.consecutiveSecondaryOnly || 0);
  const recentFailure = Number(runtime.lastFailureEpochMs || 0) >
    Number(runtime.lastCompletedEpochMs || 0);

  let status = 'HEALTHY';
  let actionRequired = false;

  if (journalTriggerCount !== 1) {
    status = 'JOURNAL_TRIGGER_INVALID';
    actionRequired = true;
  } else if (sourceTriggerCount !== 1) {
    status = 'SOURCE_TRIGGER_INVALID';
    actionRequired = true;
  } else if (lastPoll <= 0) {
    status = 'POLL_UNPROVEN';
    actionRequired = true;
  } else if (pollAgeMs > PIPELINE_HEALTH_CONFIG.maxPollAgeMs) {
    status = 'POLL_STALE';
    actionRequired = true;
  } else if (pendingOutboxEvents > 0 && oldestOutboxAgeMs > PIPELINE_HEALTH_CONFIG.maxOutboxAgeMs) {
    status = 'OUTBOX_STALLED';
    actionRequired = true;
  } else if (!source || sourceCheckedAt <= 0 || sourceCheckAgeMs > PIPELINE_HEALTH_CONFIG.maxSourceCheckAgeMs) {
    status = 'SOURCE_CROSSCHECK_STALE';
    actionRequired = true;
  } else if (
    (sourceStatus === 'SECONDARY_ONLY' || sourceStatus === 'DIVERGED') &&
    sourceGapStreak >= PIPELINE_HEALTH_CONFIG.confirmedSourceGapStreak
  ) {
    status = 'SOURCE_GAP_CONFIRMED';
    actionRequired = true;
  } else if (
    sourceStatus !== 'MATCH' &&
    sourceStatus !== 'MATCH_AFTER_RETRY'
  ) {
    status = 'SOURCE_DEGRADED';
  } else if (recentFailure) {
    status = 'RECENT_FAILURE';
  }

  return {
    schemaVersion: PIPELINE_HEALTH_CONFIG.schemaVersion,
    checkedAtEpochMs: now,
    status: status,
    healthy: status === 'HEALTHY',
    actionRequired: actionRequired,
    journalTriggerCount: journalTriggerCount,
    sourceTriggerCount: sourceTriggerCount,
    lastAttemptEpochMs: Number(runtime.lastAttemptEpochMs || 0),
    lastHkoPollSuccessEpochMs: lastPoll,
    pollAgeMs: pollAgeMs,
    lastJournalCheckEpochMs: Number(runtime.lastJournalCheckEpochMs || 0),
    lastJournalAppendEpochMs: Number(runtime.lastJournalAppendEpochMs || 0),
    latestJournalCursor: Number(runtime.latestJournalCursor || 0),
    lastOutboxFlushEpochMs: Number(runtime.lastOutboxFlushEpochMs || 0),
    pendingOutboxEvents: pendingOutboxEvents,
    oldestOutboxAgeMs: oldestOutboxAgeMs,
    lastCompletedEpochMs: Number(runtime.lastCompletedEpochMs || 0),
    lastFailureEpochMs: Number(runtime.lastFailureEpochMs || 0),
    lastError: String(runtime.lastError || '').slice(0, 500),
    sourceStatus: sourceStatus,
    sourceCheckedAtEpochMs: sourceCheckedAt,
    sourceCheckAgeMs: sourceCheckAgeMs,
    sourceGapStreak: sourceGapStreak,
    sourceSecondaryOnly: source && Array.isArray(source.secondaryOnly) ? source.secondaryOnly.slice() : [],
    sourcePrimaryOnly: source && Array.isArray(source.primaryOnly) ? source.primaryOnly.slice() : [],
  };
}

function readNotificationPipelineHealth_(properties) {
  const raw = properties.getProperty(PIPELINE_HEALTH_CONFIG.snapshotPropertyKey);
  if (!raw) return null;
  try {
    const parsed = JSON.parse(raw);
    return parsed && typeof parsed === 'object' ? parsed : null;
  } catch (_error) {
    return null;
  }
}

/** Owner-facing health verification. No credentials or HKO bodies are returned. */
function verifyNotificationPipelineHealth() {
  const properties = PropertiesService.getScriptProperties();
  const health = refreshNotificationPipelineHealth_(properties, Date.now());
  console.log(JSON.stringify(health));
  return health;
}

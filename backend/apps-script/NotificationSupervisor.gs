/**
 * Weather Metro notification supervisor.
 *
 * One one-minute Apps Script trigger owns the notification backend schedule.
 * A lightweight HKO warnsum fast poll runs every minute. Full publication
 * hydration runs immediately on warnsum change and otherwise at a bounded
 * interval so warningInfo-only statements and SWT remain covered. The
 * independent RSS cross-check is also sampled on a bounded cadence, while
 * recovery evidence/failover run only after a persistent source gap exists.
 */

const NOTIFICATION_SUPERVISOR_CONFIG = Object.freeze({
  triggerFunction: 'runNotificationSupervisor',
  runtimePropertyKey: 'HKO_NOTIFICATION_SUPERVISOR_RUNTIME_V2',
  intervalMinutes: 1,
  sourceMinIntervalMs: 170 * 1000,
  sourceDegradedMinIntervalMs: 55 * 1000,
  pruneEveryRuns: 10,
  consumerDailyTriggerRuntimeBudgetMs: 90 * 60 * 1000,
  projectedRiskFraction: 0.85,
  actualRiskFraction: 0.80,
  minimumProjectionRuns: 10,
  schemaVersion: 2,
});

const NOTIFICATION_LEGACY_TRIGGER_HANDLERS = Object.freeze([
  'checkWeatherUpdates',
  'checkWeatherUpdatesJournalled',
  'checkWarningSourceRedundancy',
  'checkSourceGapRecoveryEvidence',
  'checkSourceGapRecoveryFailover',
]);

/**
 * Production migration/setup entry point.
 *
 * Removes all notification-owned legacy triggers and installs exactly one
 * one-minute supervisor trigger. Running it in a healthy steady state does not
 * fabricate any weather event.
 */
function setupNotificationSupervisor() {
  assertNotificationSupervisorDependencies_();
  const triggers = ScriptApp.getProjectTriggers();
  triggers.forEach(function (trigger) {
    const handler = trigger.getHandlerFunction();
    if (
      handler === NOTIFICATION_SUPERVISOR_CONFIG.triggerFunction ||
      NOTIFICATION_LEGACY_TRIGGER_HANDLERS.indexOf(handler) >= 0
    ) {
      ScriptApp.deleteTrigger(trigger);
    }
  });

  ScriptApp.newTrigger(NOTIFICATION_SUPERVISOR_CONFIG.triggerFunction)
    .timeBased()
    .everyMinutes(NOTIFICATION_SUPERVISOR_CONFIG.intervalMinutes)
    .create();

  if (typeof seedPipelineJournalCursorFromSheet_ === 'function') {
    seedPipelineJournalCursorFromSheet_();
  }
  return runNotificationSupervisor();
}

/** Repairs accidentally recreated legacy notification triggers. */
function pruneLegacyNotificationTriggers_() {
  if (typeof ScriptApp === 'undefined' || !ScriptApp.getProjectTriggers) return 0;
  let removed = 0;
  ScriptApp.getProjectTriggers().forEach(function (trigger) {
    if (NOTIFICATION_LEGACY_TRIGGER_HANDLERS.indexOf(trigger.getHandlerFunction()) >= 0) {
      ScriptApp.deleteTrigger(trigger);
      removed += 1;
    }
  });
  return removed;
}

function runNotificationSupervisor() {
  assertNotificationSupervisorDependencies_();
  const startedAtEpochMs = Date.now();
  const properties = PropertiesService.getScriptProperties();
  const runtimeBefore = readNotificationSupervisorRuntime_(properties);
  const userLock = LockService.getUserLock();

  if (!userLock.tryLock(5000)) {
    const skipped = {
      schemaVersion: NOTIFICATION_SUPERVISOR_CONFIG.schemaVersion,
      startedAtEpochMs: startedAtEpochMs,
      completedAtEpochMs: Date.now(),
      status: 'SKIPPED_BUSY',
      journal: 'SKIPPED',
      journalReason: '',
      source: 'SKIPPED',
      recovery: 'SKIPPED',
      failures: [],
      legacyTriggersRemoved: 0,
      pruneChecked: false,
    };
    updateNotificationSupervisorRuntime_(properties, skipped, runtimeBefore);
    console.warn('Notification supervisor skipped because a previous cycle is still running.');
    return skipped;
  }

  const result = {
    schemaVersion: NOTIFICATION_SUPERVISOR_CONFIG.schemaVersion,
    startedAtEpochMs: startedAtEpochMs,
    completedAtEpochMs: 0,
    status: 'OK',
    journal: 'PENDING',
    journalReason: '',
    source: 'NOT_DUE',
    recovery: 'IDLE',
    failures: [],
    legacyTriggersRemoved: 0,
    pruneChecked: false,
  };
  let primarySummary = null;

  try {
    if (shouldPruneLegacyNotificationTriggers_(runtimeBefore)) {
      result.pruneChecked = true;
      result.legacyTriggersRemoved = pruneLegacyNotificationTriggers_();
    }

    try {
      const journalCycle = runNotificationFastPoll_(properties, Date.now());
      result.journal = String(journalCycle && journalCycle.mode || 'UNKNOWN');
      result.journalReason = String(journalCycle && journalCycle.reason || '');
      primarySummary = journalCycle && journalCycle.primarySummary
        ? journalCycle.primarySummary
        : null;
    } catch (error) {
      result.journal = 'ERROR';
      result.failures.push(supervisorFailure_('journal', error));
    }

    const sourceBefore = readSupervisorSourceHealth_(properties);
    const forceSourceCheck = result.journal === 'ERROR';
    if (shouldRunSourceCrossCheck_(sourceBefore, Date.now(), forceSourceCheck)) {
      try {
        const source = primarySummary && typeof checkWarningSourceRedundancyFromSummary_ === 'function'
          ? checkWarningSourceRedundancyFromSummary_(primarySummary, Date.now())
          : checkWarningSourceRedundancy();
        result.source = String(source && source.status || 'UNKNOWN');
        if (shouldRunSourceGapRecovery_(source)) {
          const evidence = checkSourceGapRecoveryEvidence();
          result.recovery = String(evidence && evidence.status || 'UNKNOWN');
          if (
            typeof recoveryFailoverEnabled_ === 'function' &&
            recoveryFailoverEnabled_(properties)
          ) {
            const failover = checkSourceGapRecoveryFailover();
            result.recovery = result.recovery + ' / ' + String(failover && failover.status || 'UNKNOWN');
          }
        }
      } catch (error) {
        result.source = 'ERROR';
        result.failures.push(supervisorFailure_('source/recovery', error));
      }
    }

    // Normal health reads are derived live by verify/doGet. Avoid another
    // Apps Script service round-trip every minute; refresh eagerly on failure.
    if (result.failures.length > 0 && typeof refreshNotificationPipelineHealth_ === 'function') {
      try {
        refreshNotificationPipelineHealth_(properties, Date.now());
      } catch (error) {
        result.failures.push(supervisorFailure_('health', error));
      }
    }
  } finally {
    result.completedAtEpochMs = Date.now();
    if (result.failures.length > 0) result.status = 'DEGRADED';
    updateNotificationSupervisorRuntime_(properties, result, runtimeBefore);
    userLock.releaseLock();
  }

  if (result.failures.length > 0) {
    console.error('Notification supervisor degraded: ' + JSON.stringify(result));
  } else {
    console.log('Notification supervisor completed: ' + JSON.stringify(result));
  }
  return result;
}

function shouldRunSourceCrossCheck_(sourceHealth, nowEpochMs, force) {
  if (force === true) return true;
  const checkedAt = Number(sourceHealth && sourceHealth.checkedAtEpochMs || 0);
  if (!Number.isFinite(checkedAt) || checkedAt <= 0) return true;
  const status = String(sourceHealth && sourceHealth.status || '');
  const healthy = status === 'MATCH' || status === 'MATCH_AFTER_RETRY';
  const interval = healthy
    ? NOTIFICATION_SUPERVISOR_CONFIG.sourceMinIntervalMs
    : NOTIFICATION_SUPERVISOR_CONFIG.sourceDegradedMinIntervalMs;
  return Number(nowEpochMs || Date.now()) - checkedAt >= interval;
}

function shouldRunSourceGapRecovery_(sourceHealth) {
  if (!sourceHealth) return false;
  const status = String(sourceHealth.status || '');
  const streak = Number(sourceHealth.consecutiveSecondaryOnly || 0);
  const required = Array.isArray(sourceHealth.secondaryOnly) ? sourceHealth.secondaryOnly : [];
  return (
    (status === 'SECONDARY_ONLY' || status === 'DIVERGED') &&
    streak >= 2 &&
    required.length > 0
  );
}

function shouldPruneLegacyNotificationTriggers_(runtime) {
  const runCount = Math.max(0, Number(runtime && runtime.dayRunCount || 0));
  return runCount === 0 || runCount % NOTIFICATION_SUPERVISOR_CONFIG.pruneEveryRuns === 0;
}

function readSupervisorSourceHealth_(properties) {
  if (typeof readWarningSourceCrossCheck_ === 'function') {
    return readWarningSourceCrossCheck_(properties);
  }
  const raw = properties.getProperty('HKO_WARNING_SOURCE_CROSSCHECK_HEALTH_V1');
  if (!raw) return null;
  try {
    const parsed = JSON.parse(raw);
    return parsed && typeof parsed === 'object' ? parsed : null;
  } catch (_error) {
    return null;
  }
}

function supervisorFailure_(component, error) {
  return {
    component: component,
    message: String(error && error.message ? error.message : error || 'Unknown error').slice(0, 300),
  };
}

function updateNotificationSupervisorRuntime_(properties, result, existingRuntime) {
  const completedAt = Number(result.completedAtEpochMs || Date.now());
  const startedAt = Number(result.startedAtEpochMs || completedAt);
  const durationMs = Math.max(0, completedAt - startedAt);
  const dayKey = notificationSupervisorHongKongDayKey_(completedAt);
  let runtime = existingRuntime || readNotificationSupervisorRuntime_(properties);
  if (runtime.dayKey !== dayKey) {
    runtime = notificationSupervisorEmptyRuntime_(dayKey);
  }

  runtime.dayRunCount += 1;
  runtime.dayRuntimeMs += durationMs;
  runtime.maxDurationMs = Math.max(runtime.maxDurationMs, durationMs);
  runtime.lastDurationMs = durationMs;
  runtime.lastStartedAtEpochMs = startedAt;
  runtime.lastCompletedAtEpochMs = completedAt;
  runtime.lastStatus = String(result.status || 'UNKNOWN');
  runtime.legacyTriggersRemovedToday += Math.max(0, Number(result.legacyTriggersRemoved || 0));
  if (result.pruneChecked) runtime.pruneChecksToday += 1;
  if (result.journal === 'FAST') runtime.fastJournalChecksToday += 1;
  if (result.journal === 'FULL') runtime.fullJournalChecksToday += 1;
  if (result.journal === 'ERROR') runtime.journalFailuresToday += 1;
  if (result.source !== 'NOT_DUE' && result.source !== 'SKIPPED') runtime.sourceChecksToday += 1;
  if (result.recovery !== 'IDLE' && result.recovery !== 'SKIPPED') runtime.recoveryChecksToday += 1;
  if (result.status === 'SKIPPED_BUSY') runtime.busySkipsToday += 1;
  runtime.componentFailuresToday += Array.isArray(result.failures) ? result.failures.length : 0;
  runtime.lastFailures = Array.isArray(result.failures) ? result.failures.slice(0, 5) : [];
  writeNotificationSupervisorRuntime_(properties, runtime);
  return runtime;
}

function notificationSupervisorEmptyRuntime_(dayKey) {
  return {
    schemaVersion: NOTIFICATION_SUPERVISOR_CONFIG.schemaVersion,
    dayKey: dayKey || '',
    dayRunCount: 0,
    dayRuntimeMs: 0,
    maxDurationMs: 0,
    lastDurationMs: 0,
    lastStartedAtEpochMs: 0,
    lastCompletedAtEpochMs: 0,
    lastStatus: '',
    fastJournalChecksToday: 0,
    fullJournalChecksToday: 0,
    journalFailuresToday: 0,
    sourceChecksToday: 0,
    recoveryChecksToday: 0,
    pruneChecksToday: 0,
    busySkipsToday: 0,
    componentFailuresToday: 0,
    legacyTriggersRemovedToday: 0,
    lastFailures: [],
  };
}

function readNotificationSupervisorRuntime_(properties) {
  const raw = properties.getProperty(NOTIFICATION_SUPERVISOR_CONFIG.runtimePropertyKey);
  if (!raw) return notificationSupervisorEmptyRuntime_('');
  try {
    const parsed = JSON.parse(raw);
    if (!parsed || typeof parsed !== 'object') throw new Error('not an object');
    return Object.assign(notificationSupervisorEmptyRuntime_(String(parsed.dayKey || '')), parsed);
  } catch (_error) {
    return notificationSupervisorEmptyRuntime_('');
  }
}

function writeNotificationSupervisorRuntime_(properties, runtime) {
  properties.setProperty(
    NOTIFICATION_SUPERVISOR_CONFIG.runtimePropertyKey,
    JSON.stringify(runtime),
  );
}

function deriveNotificationSupervisorQuotaTelemetry_(runtime) {
  const value = runtime || notificationSupervisorEmptyRuntime_('');
  const runCount = Math.max(0, Number(value.dayRunCount || 0));
  const runtimeMs = Math.max(0, Number(value.dayRuntimeMs || 0));
  const averageRunMs = runCount > 0 ? runtimeMs / runCount : 0;
  const projectedDailyRuntimeMs = runCount >= NOTIFICATION_SUPERVISOR_CONFIG.minimumProjectionRuns
    ? averageRunMs * (24 * 60 / NOTIFICATION_SUPERVISOR_CONFIG.intervalMinutes)
    : 0;
  const budget = NOTIFICATION_SUPERVISOR_CONFIG.consumerDailyTriggerRuntimeBudgetMs;
  const actualRisk = runtimeMs >= budget * NOTIFICATION_SUPERVISOR_CONFIG.actualRiskFraction;
  const projectedRisk = projectedDailyRuntimeMs >= budget * NOTIFICATION_SUPERVISOR_CONFIG.projectedRiskFraction;
  return {
    dayKey: String(value.dayKey || ''),
    dayRunCount: runCount,
    dayRuntimeMs: runtimeMs,
    averageRunMs: Math.round(averageRunMs),
    projectedDailyRuntimeMs: Math.round(projectedDailyRuntimeMs),
    consumerDailyReferenceBudgetMs: budget,
    consumerQuotaRisk: actualRisk || projectedRisk,
    actualRuntimeRisk: actualRisk,
    projectedRuntimeRisk: projectedRisk,
    fastJournalChecksToday: Math.max(0, Number(value.fastJournalChecksToday || 0)),
    fullJournalChecksToday: Math.max(0, Number(value.fullJournalChecksToday || 0)),
    journalFailuresToday: Math.max(0, Number(value.journalFailuresToday || 0)),
    sourceChecksToday: Math.max(0, Number(value.sourceChecksToday || 0)),
    recoveryChecksToday: Math.max(0, Number(value.recoveryChecksToday || 0)),
    pruneChecksToday: Math.max(0, Number(value.pruneChecksToday || 0)),
    busySkipsToday: Math.max(0, Number(value.busySkipsToday || 0)),
    componentFailuresToday: Math.max(0, Number(value.componentFailuresToday || 0)),
    legacyTriggersRemovedToday: Math.max(0, Number(value.legacyTriggersRemovedToday || 0)),
    lastDurationMs: Math.max(0, Number(value.lastDurationMs || 0)),
    maxDurationMs: Math.max(0, Number(value.maxDurationMs || 0)),
    lastStatus: String(value.lastStatus || ''),
  };
}

function notificationSupervisorHongKongDayKey_(epochMs) {
  const offsetEpochMs = Number(epochMs || Date.now()) + 8 * 60 * 60 * 1000;
  return new Date(offsetEpochMs).toISOString().slice(0, 10);
}

function notificationSupervisorTriggerSummary_() {
  const triggers = ScriptApp.getProjectTriggers();
  const supervisorCount = triggers.filter(function (trigger) {
    return trigger.getHandlerFunction() === NOTIFICATION_SUPERVISOR_CONFIG.triggerFunction;
  }).length;
  const legacy = triggers.filter(function (trigger) {
    return NOTIFICATION_LEGACY_TRIGGER_HANDLERS.indexOf(trigger.getHandlerFunction()) >= 0;
  });
  return {
    supervisorTriggerCount: supervisorCount,
    legacyTriggerCount: legacy.length,
    legacyHandlers: legacy.map(function (trigger) { return trigger.getHandlerFunction(); }).sort(),
  };
}

/** Owner-facing verification. No credentials or HKO publication bodies are returned. */
function verifyNotificationSupervisor() {
  const properties = PropertiesService.getScriptProperties();
  const triggerSummary = notificationSupervisorTriggerSummary_();
  const runtime = readNotificationSupervisorRuntime_(properties);
  const result = {
    schemaVersion: NOTIFICATION_SUPERVISOR_CONFIG.schemaVersion,
    supervisorTriggerCount: triggerSummary.supervisorTriggerCount,
    legacyTriggerCount: triggerSummary.legacyTriggerCount,
    legacyHandlers: triggerSummary.legacyHandlers,
    sourceMinIntervalMs: NOTIFICATION_SUPERVISOR_CONFIG.sourceMinIntervalMs,
    sourceDegradedMinIntervalMs: NOTIFICATION_SUPERVISOR_CONFIG.sourceDegradedMinIntervalMs,
    fastPoll: typeof notificationFastPollVerification_ === 'function'
      ? notificationFastPollVerification_()
      : null,
    runtime: runtime,
    quota: deriveNotificationSupervisorQuotaTelemetry_(runtime),
    pipelineHealth: typeof refreshNotificationPipelineHealth_ === 'function'
      ? refreshNotificationPipelineHealth_(properties, Date.now())
      : null,
  };
  console.log(JSON.stringify(result));
  return result;
}

function assertNotificationSupervisorDependencies_() {
  const required = [
    ['runNotificationFastPoll_', typeof runNotificationFastPoll_ === 'function'],
    ['checkWarningSourceRedundancy', typeof checkWarningSourceRedundancy === 'function'],
    ['checkSourceGapRecoveryEvidence', typeof checkSourceGapRecoveryEvidence === 'function'],
    ['checkSourceGapRecoveryFailover', typeof checkSourceGapRecoveryFailover === 'function'],
  ];
  const missing = required.filter(function (entry) { return !entry[1]; }).map(function (entry) { return entry[0]; });
  if (missing.length > 0) {
    throw new Error('Notification supervisor dependency missing: ' + missing.join(', '));
  }
}

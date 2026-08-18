/**
 * Weather Metro quota-safe notification fast poll.
 *
 * The primary HKO warning summary remains checked every supervisor minute.
 * Expensive full publication hydration (warningInfo + SWT + durable journal
 * reconciliation) runs immediately when warnsum changes and otherwise at a
 * bounded background interval so standalone HKO statements/tips are still
 * eventually captured.
 *
 * The independent RSS cross-check can reuse the warnsum snapshot already fetched
 * by this cycle. If RSS disagrees, the existing retry logic still re-reads the
 * primary JSON source before any recovery path can act.
 */

const NOTIFICATION_FAST_POLL_CONFIG = Object.freeze({
  statePropertyKey: 'HKO_NOTIFICATION_FAST_POLL_V2',
  legacyStatePropertyKey: 'HKO_NOTIFICATION_FAST_POLL_V1',
  fullRefreshIntervalMs: 170 * 1000,
  primarySuccessTelemetryMinIntervalMs: 90 * 1000,
  schemaVersion: 2,
});

function runNotificationFastPoll_(properties, nowEpochMs) {
  const now = Number(nowEpochMs || Date.now());
  assertNotificationFastPollDependencies_();
  notificationFastPollMigrateV2_(properties);

  let summary;
  try {
    const request = hkoRequest_('warnsum');
    const response = UrlFetchApp.fetch(request.url, notificationFetchParams_(request));
    summary = parseHkoResponse_(response);
  } catch (error) {
    notificationFastPollMarkPrimaryFailure_(properties, now, error);
    throw error;
  }

  const summaryDigest = notificationWarnsumDigest_(summary);
  const state = readNotificationFastPollState_(properties);
  const lastFull = Number(state.lastFullRefreshEpochMs || 0);
  const fullAgeMs = lastFull > 0 ? Math.max(0, now - lastFull) : null;
  const reason = !state.committedSummaryDigest
    ? 'BOOTSTRAP'
    : state.committedSummaryDigest !== summaryDigest
      ? 'WARNSUM_CHANGED'
      : fullAgeMs === null || fullAgeMs >= NOTIFICATION_FAST_POLL_CONFIG.fullRefreshIntervalMs
        ? 'AUX_REFRESH_DUE'
        : '';

  if (reason) {
    // Do not advance the committed digest until the full authoritative journal
    // pass succeeds. A failed hydration is therefore retried next supervisor run.
    // Prefer the optimized owner that reuses this exact warnsum snapshot and only
    // fetches warningInfo + SWT. The legacy full owner remains a safe fallback.
    if (typeof checkWeatherUpdatesJournalledFromSummary_ === 'function') {
      checkWeatherUpdatesJournalledFromSummary_(summary);
    } else {
      checkWeatherUpdatesJournalled();
    }
    writeNotificationFastPollState_(properties, {
      schemaVersion: NOTIFICATION_FAST_POLL_CONFIG.schemaVersion,
      committedSummaryDigest: summaryDigest,
      lastFullRefreshEpochMs: Date.now(),
    });
    return {
      mode: 'FULL',
      reason: reason,
      primarySummary: summary,
      summaryDigest: summaryDigest,
      fullAgeMs: 0,
    };
  }

  // The HKO request still runs every supervisor minute. Persist compact success
  // telemetry only often enough to stay safely inside the 3-minute health gate;
  // this avoids a redundant Script Properties write on alternating healthy runs.
  notificationFastPollMarkPrimarySuccess_(properties, now);

  // FCM delivery retries must not wait for the next full source hydration.
  const outbox = typeof readOutbox_ === 'function' ? readOutbox_(properties) : [];
  if (outbox.length > 0 && typeof flushJournalOutbox_ === 'function') {
    const retry = flushJournalOutbox_(properties, Date.now());
    if (typeof notificationPipelineMarkFlush_ === 'function') {
      notificationPipelineMarkFlush_(properties, Date.now(), retry.pending, retry.failed);
    }
    if (retry.failed > 0) {
      const error = new Error(retry.failed + ' queued FCM wake-up attempt(s) failed and will be retried.');
      if (typeof notificationPipelineMarkFailure_ === 'function') {
        notificationPipelineMarkFailure_(properties, Date.now(), error);
      }
      throw error;
    }
  }

  return {
    mode: 'FAST',
    reason: 'WARNSUM_UNCHANGED',
    primarySummary: summary,
    summaryDigest: summaryDigest,
    fullAgeMs: fullAgeMs,
  };
}

function notificationFetchParams_(request) {
  const params = {};
  Object.keys(request || {}).forEach(function (key) {
    if (key !== 'url') params[key] = request[key];
  });
  return params;
}

/**
 * V2 switches full hydration to the prefetched-warnsum owner. Reset the V2
 * supervisor runtime once so the post-deployment soak is not contaminated by
 * the pre-optimization measurements. The durable journal/source state is never
 * reset here.
 */
function notificationFastPollMigrateV2_(properties) {
  if (properties.getProperty(NOTIFICATION_FAST_POLL_CONFIG.statePropertyKey) !== null) return;
  const legacy = properties.getProperty(NOTIFICATION_FAST_POLL_CONFIG.legacyStatePropertyKey);
  if (legacy === null) return;
  if (typeof properties.deleteProperty === 'function') {
    properties.deleteProperty(NOTIFICATION_FAST_POLL_CONFIG.legacyStatePropertyKey);
    properties.deleteProperty('HKO_NOTIFICATION_SUPERVISOR_RUNTIME_V2');
  }
}

function notificationFastPollShouldPersistPrimarySuccess_(lastSuccessEpochMs, nowEpochMs) {
  const previous = Number(lastSuccessEpochMs || 0);
  const now = Number(nowEpochMs || Date.now());
  if (!Number.isFinite(previous) || previous <= 0) return true;
  if (!Number.isFinite(now) || now <= 0) return true;
  const ageMs = now - previous;
  if (ageMs < 0) return true;
  return ageMs >= NOTIFICATION_FAST_POLL_CONFIG.primarySuccessTelemetryMinIntervalMs;
}

function notificationFastPollMarkPrimarySuccess_(properties, nowEpochMs) {
  const now = Number(nowEpochMs || Date.now());
  if (
    typeof readNotificationPipelineRuntime_ === 'function' &&
    typeof writeNotificationPipelineRuntime_ === 'function'
  ) {
    const runtime = readNotificationPipelineRuntime_(properties);
    if (!notificationFastPollShouldPersistPrimarySuccess_(runtime.lastHkoPollSuccessEpochMs, now)) {
      return;
    }
    runtime.lastAttemptEpochMs = now;
    runtime.lastHkoPollSuccessEpochMs = now;
    writeNotificationPipelineRuntime_(properties, runtime);
    return;
  }
  if (typeof notificationPipelineMarkAttempt_ === 'function') {
    notificationPipelineMarkAttempt_(properties, now);
  }
  if (typeof notificationPipelineMarkSourceSuccess_ === 'function') {
    notificationPipelineMarkSourceSuccess_(properties, now);
  }
}

function notificationFastPollMarkPrimaryFailure_(properties, nowEpochMs, error) {
  const now = Number(nowEpochMs || Date.now());
  if (
    typeof readNotificationPipelineRuntime_ === 'function' &&
    typeof writeNotificationPipelineRuntime_ === 'function'
  ) {
    const runtime = readNotificationPipelineRuntime_(properties);
    runtime.lastAttemptEpochMs = now;
    runtime.lastFailureEpochMs = now;
    runtime.lastError = String(error && error.message ? error.message : error || 'Unknown fast-poll failure')
      .slice(0, 500);
    writeNotificationPipelineRuntime_(properties, runtime);
    return;
  }
  if (typeof notificationPipelineMarkAttempt_ === 'function') {
    notificationPipelineMarkAttempt_(properties, now);
  }
  if (typeof notificationPipelineMarkFailure_ === 'function') {
    notificationPipelineMarkFailure_(properties, now, error);
  }
}

function notificationWarnsumDigest_(summary) {
  const value = summary && typeof summary === 'object' ? summary : {};
  const canonical = Object.keys(value).sort().map(function (family) {
    const row = value[family] || {};
    return [
      String(family || ''),
      String(row.code || ''),
      String(row.actionCode || ''),
      String(row.type || row.name || ''),
      String(row.issueTime || ''),
      String(row.expireTime || ''),
      String(row.updateTime || ''),
    ].join('|');
  }).join('\n');
  return digest_(canonical);
}

function readNotificationFastPollState_(properties) {
  const raw = properties.getProperty(NOTIFICATION_FAST_POLL_CONFIG.statePropertyKey);
  if (!raw) {
    return {
      schemaVersion: NOTIFICATION_FAST_POLL_CONFIG.schemaVersion,
      committedSummaryDigest: '',
      lastFullRefreshEpochMs: 0,
    };
  }
  try {
    const parsed = JSON.parse(raw);
    if (!parsed || typeof parsed !== 'object') throw new Error('not an object');
    return {
      schemaVersion: NOTIFICATION_FAST_POLL_CONFIG.schemaVersion,
      committedSummaryDigest: String(parsed.committedSummaryDigest || ''),
      lastFullRefreshEpochMs: Math.max(0, Number(parsed.lastFullRefreshEpochMs || 0)),
    };
  } catch (_error) {
    return {
      schemaVersion: NOTIFICATION_FAST_POLL_CONFIG.schemaVersion,
      committedSummaryDigest: '',
      lastFullRefreshEpochMs: 0,
    };
  }
}

function writeNotificationFastPollState_(properties, state) {
  properties.setProperty(
    NOTIFICATION_FAST_POLL_CONFIG.statePropertyKey,
    JSON.stringify({
      schemaVersion: NOTIFICATION_FAST_POLL_CONFIG.schemaVersion,
      committedSummaryDigest: String(state.committedSummaryDigest || ''),
      lastFullRefreshEpochMs: Math.max(0, Number(state.lastFullRefreshEpochMs || 0)),
    }),
  );
}

/**
 * Secondary-source check using the primary warnsum object already fetched this
 * supervisor cycle. Normal MATCH costs one RSS request instead of another JSON
 * request. Any RSS-only discrepancy still invokes retryPrimarySourceGap_(),
 * which performs bounded fresh primary JSON reads before the mismatch persists.
 */
function checkWarningSourceRedundancyFromSummary_(summary, checkedAtEpochMs) {
  const properties = PropertiesService.getScriptProperties();
  const checkedAt = Number(checkedAtEpochMs || Date.now());
  let result = {
    schemaVersion: SOURCE_REDUNDANCY_CONFIG.schemaVersion,
    checkedAtEpochMs: checkedAt,
    primaryOk: true,
    secondaryOk: false,
    primaryTokens: warningTokensFromSummary_(summary),
    secondaryTokens: [],
    secondaryOnly: [],
    primaryOnly: [],
    consecutiveSecondaryOnly: 0,
    secondaryOnlySignature: '',
    primaryRetryAttempts: 0,
    recoveredAfterPrimaryRetry: false,
    initialStatus: '',
    primaryError: '',
    secondaryError: '',
    secondaryDigest: '',
  };

  try {
    const response = UrlFetchApp.fetch(SOURCE_REDUNDANCY_CONFIG.warningSummaryRssUrl, {
      method: 'get',
      headers: {
        Accept: 'application/rss+xml, application/xml, text/xml, */*',
        'Cache-Control': 'no-cache',
      },
      muteHttpExceptions: true,
    });
    const secondaryText = parseCrossCheckRssResponse_(response);
    result.secondaryOk = true;
    result.secondaryTokens = warningTokensFromText_(secondaryText);
    result.secondaryDigest = digest_(secondaryText);
    result = applyWarningTokenComparison_(result);
    result = retryPrimarySourceGap_(result);
  } catch (error) {
    result.status = 'SECONDARY_ERROR';
    result.secondaryError = String(error && error.message ? error.message : error).slice(0, 300);
  }

  const stored = recordWarningSourceCrossCheck_(properties, result);
  if (stored.status === 'SECONDARY_ONLY' || stored.status === 'DIVERGED') {
    console.error('HKO source cross-check mismatch: ' + JSON.stringify(stored));
  } else if (stored.status !== 'MATCH' && stored.status !== 'MATCH_AFTER_RETRY') {
    console.warn('HKO source cross-check status: ' + JSON.stringify(stored));
  } else {
    console.log('HKO source cross-check matched: ' + JSON.stringify(stored));
  }
  return stored;
}

function notificationFastPollVerification_() {
  const properties = PropertiesService.getScriptProperties();
  const state = readNotificationFastPollState_(properties);
  const now = Date.now();
  const lastFull = Number(state.lastFullRefreshEpochMs || 0);
  return {
    schemaVersion: NOTIFICATION_FAST_POLL_CONFIG.schemaVersion,
    fullRefreshIntervalMs: NOTIFICATION_FAST_POLL_CONFIG.fullRefreshIntervalMs,
    primarySuccessTelemetryMinIntervalMs: NOTIFICATION_FAST_POLL_CONFIG.primarySuccessTelemetryMinIntervalMs,
    optimizedHydrationAvailable: typeof checkWeatherUpdatesJournalledFromSummary_ === 'function',
    committedSummaryDigestPresent: Boolean(state.committedSummaryDigest),
    lastFullRefreshEpochMs: lastFull,
    fullRefreshAgeMs: lastFull > 0 ? Math.max(0, now - lastFull) : null,
  };
}

function assertNotificationFastPollDependencies_() {
  const required = [
    ['hkoRequest_', typeof hkoRequest_ === 'function'],
    ['parseHkoResponse_', typeof parseHkoResponse_ === 'function'],
    ['digest_', typeof digest_ === 'function'],
    [
      'journal hydration owner',
      typeof checkWeatherUpdatesJournalledFromSummary_ === 'function' ||
        typeof checkWeatherUpdatesJournalled === 'function',
    ],
  ];
  const missing = required.filter(function (entry) { return !entry[1]; }).map(function (entry) { return entry[0]; });
  if (missing.length > 0) {
    throw new Error('Notification fast-poll dependency missing: ' + missing.join(', '));
  }
}

/**
 * Weather Metro confirmed source-gap recovery evidence.
 *
 * Phase 2C3A does not synthesize user-visible alerts from RSS. When the warning
 * summary cross-check reports a persistent RSS-only gap, this layer asks a
 * second, detailed HKO RSS publication path for independent evidence and stores
 * only compact metadata. This gives us production proof before enabling any
 * automatic recovery notification semantics.
 */

const SOURCE_GAP_RECOVERY_CONFIG = Object.freeze({
  triggerFunction: 'checkSourceGapRecoveryEvidence',
  detailedWarningRssUrl: 'https://rss.weather.gov.hk/rss/WeatherWarningBulletin_uc.xml',
  evidencePropertyKey: 'HKO_SOURCE_GAP_RECOVERY_EVIDENCE_V1',
  intervalMinutes: 1,
  confirmedGapStreak: 2,
  schemaVersion: 1,
});

/** Installs one one-minute recovery-evidence trigger and repairs cursor telemetry. */
function setupSourceGapRecoveryEvidence() {
  ScriptApp.getProjectTriggers()
    .filter(function (trigger) {
      return trigger.getHandlerFunction() === SOURCE_GAP_RECOVERY_CONFIG.triggerFunction;
    })
    .forEach(function (trigger) {
      ScriptApp.deleteTrigger(trigger);
    });

  ScriptApp.newTrigger(SOURCE_GAP_RECOVERY_CONFIG.triggerFunction)
    .timeBased()
    .everyMinutes(SOURCE_GAP_RECOVERY_CONFIG.intervalMinutes)
    .create();

  seedPipelineJournalCursorFromSheet_();
  checkSourceGapRecoveryEvidence();
}

/**
 * Confirms a persistent summary-RSS-only warning against HKO's detailed warning
 * RSS. No event is journalled and no FCM message is sent in this checkpoint.
 */
function checkSourceGapRecoveryEvidence() {
  const properties = PropertiesService.getScriptProperties();
  const checkedAtEpochMs = Date.now();
  const source = readSourceCrossCheckForRecovery_(properties);
  const requiredTokens = source && Array.isArray(source.secondaryOnly)
    ? source.secondaryOnly.slice().sort()
    : [];
  const sourceStatus = source && source.status ? String(source.status) : 'UNAVAILABLE';
  const streak = Number(source && source.consecutiveSecondaryOnly || 0);

  if (
    (sourceStatus !== 'SECONDARY_ONLY' && sourceStatus !== 'DIVERGED') ||
    streak < SOURCE_GAP_RECOVERY_CONFIG.confirmedGapStreak ||
    requiredTokens.length === 0
  ) {
    return writeSourceGapRecoveryEvidence_(properties, {
      schemaVersion: SOURCE_GAP_RECOVERY_CONFIG.schemaVersion,
      checkedAtEpochMs: checkedAtEpochMs,
      status: 'IDLE',
      sourceStatus: sourceStatus,
      sourceCheckedAtEpochMs: Number(source && source.checkedAtEpochMs || 0),
      sourceGapStreak: streak,
      requiredTokens: requiredTokens,
      detailTokens: [],
      confirmedTokens: [],
      missingTokens: [],
      detailDigest: '',
      detailError: '',
    });
  }

  let detailText = '';
  let detailTokens = [];
  let detailError = '';
  try {
    const response = UrlFetchApp.fetch(SOURCE_GAP_RECOVERY_CONFIG.detailedWarningRssUrl, {
      method: 'get',
      headers: {
        Accept: 'application/rss+xml, application/xml, text/xml, */*',
        'Cache-Control': 'no-cache',
      },
      muteHttpExceptions: true,
    });
    detailText = parseDetailedWarningRss_(response);
    if (typeof warningTokensFromText_ !== 'function') {
      throw new Error('Warning token normalizer is unavailable.');
    }
    detailTokens = warningTokensFromText_(detailText);
  } catch (error) {
    detailError = String(error && error.message ? error.message : error).slice(0, 300);
  }

  const confirmedTokens = requiredTokens.filter(function (token) {
    return detailTokens.indexOf(token) >= 0;
  });
  const missingTokens = requiredTokens.filter(function (token) {
    return detailTokens.indexOf(token) < 0;
  });

  let status;
  if (detailError) {
    status = 'DETAIL_ERROR';
  } else if (confirmedTokens.length === requiredTokens.length) {
    status = 'DETAIL_CONFIRMED';
  } else if (confirmedTokens.length > 0) {
    status = 'DETAIL_PARTIAL';
  } else {
    status = 'DETAIL_MISSING';
  }

  const evidence = writeSourceGapRecoveryEvidence_(properties, {
    schemaVersion: SOURCE_GAP_RECOVERY_CONFIG.schemaVersion,
    checkedAtEpochMs: checkedAtEpochMs,
    status: status,
    sourceStatus: sourceStatus,
    sourceCheckedAtEpochMs: Number(source && source.checkedAtEpochMs || 0),
    sourceGapStreak: streak,
    requiredTokens: requiredTokens,
    detailTokens: detailTokens.slice().sort(),
    confirmedTokens: confirmedTokens,
    missingTokens: missingTokens,
    detailDigest: detailText ? digest_(detailText) : '',
    detailError: detailError,
  });

  if (status === 'DETAIL_CONFIRMED') {
    console.error('Persistent HKO source gap independently confirmed: ' + JSON.stringify(evidence));
  } else if (status !== 'IDLE') {
    console.warn('HKO source-gap recovery evidence: ' + JSON.stringify(evidence));
  }
  return evidence;
}

function parseDetailedWarningRss_(response) {
  if (!response || typeof response.getResponseCode !== 'function') {
    throw new Error('Missing detailed HKO warning RSS response.');
  }
  const code = Number(response.getResponseCode());
  if (code < 200 || code >= 300) {
    throw new Error('Detailed HKO warning RSS returned HTTP ' + code);
  }
  const raw = String(response.getContentText('UTF-8') || '');
  if (!raw.trim()) throw new Error('Detailed HKO warning RSS returned an empty body.');
  const visible = typeof rssVisibleText_ === 'function'
    ? rssVisibleText_(raw)
    : recoveryVisibleText_(raw);
  if (!visible) throw new Error('Detailed HKO warning RSS did not contain visible text.');
  return visible;
}

function recoveryVisibleText_(xml) {
  return String(xml || '')
    .replace(/<!\[CDATA\[([\s\S]*?)\]\]>/gi, '$1')
    .replace(/<br\s*\/?>/gi, '\n')
    .replace(/<\/p\s*>/gi, '\n')
    .replace(/<[^>]+>/g, ' ')
    .replace(/&nbsp;/gi, ' ')
    .replace(/&amp;/gi, '&')
    .replace(/&lt;/gi, '<')
    .replace(/&gt;/gi, '>')
    .replace(/&quot;/gi, '"')
    .replace(/&apos;/gi, "'")
    .replace(/\r\n?/g, '\n')
    .replace(/[\t\f\v ]+/g, ' ')
    .replace(/ *\n */g, '\n')
    .replace(/\n{3,}/g, '\n\n')
    .trim();
}

function readSourceCrossCheckForRecovery_(properties) {
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

function writeSourceGapRecoveryEvidence_(properties, evidence) {
  properties.setProperty(
    SOURCE_GAP_RECOVERY_CONFIG.evidencePropertyKey,
    JSON.stringify(evidence),
  );
  return evidence;
}

function readSourceGapRecoveryEvidence_(properties) {
  const raw = properties.getProperty(SOURCE_GAP_RECOVERY_CONFIG.evidencePropertyKey);
  if (!raw) return null;
  try {
    const parsed = JSON.parse(raw);
    return parsed && typeof parsed === 'object' ? parsed : null;
  } catch (_error) {
    return null;
  }
}

/**
 * Repairs the health runtime's journal cursor from the authoritative Google
 * Sheet tail. This fixes the observability edge where health instrumentation is
 * installed after the journal already contains events.
 */
function seedPipelineJournalCursorFromSheet_() {
  if (
    typeof PropertiesService === 'undefined' ||
    typeof ensureJournalSheet_ !== 'function' ||
    typeof readNotificationPipelineRuntime_ !== 'function' ||
    typeof writeNotificationPipelineRuntime_ !== 'function'
  ) {
    return 0;
  }
  const properties = PropertiesService.getScriptProperties();
  const sheet = ensureJournalSheet_(properties);
  const cursor = Math.max(0, Number(sheet.getLastRow() || 0) - 1);
  const runtime = readNotificationPipelineRuntime_(properties);
  if (cursor > Number(runtime.latestJournalCursor || 0)) {
    runtime.latestJournalCursor = cursor;
    writeNotificationPipelineRuntime_(properties, runtime);
  }
  return cursor;
}

/** Owner-facing verification. No HKO body or credentials are exposed. */
function verifySourceGapRecoveryEvidence() {
  const properties = PropertiesService.getScriptProperties();
  const authoritativeJournalCursor = seedPipelineJournalCursorFromSheet_();
  const result = {
    detailedWarningRssUrl: SOURCE_GAP_RECOVERY_CONFIG.detailedWarningRssUrl,
    triggerCount: ScriptApp.getProjectTriggers().filter(function (trigger) {
      return trigger.getHandlerFunction() === SOURCE_GAP_RECOVERY_CONFIG.triggerFunction;
    }).length,
    authoritativeJournalCursor: authoritativeJournalCursor,
    evidence: readSourceGapRecoveryEvidence_(properties),
  };
  console.log(JSON.stringify(result));
  return result;
}

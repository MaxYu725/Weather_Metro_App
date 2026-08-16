/**
 * Weather Metro guarded HKO source-gap failover.
 *
 * This layer is intentionally conservative. It only creates a user-visible
 * recovery notification after the independent warning-summary RSS path has
 * remained ahead of the primary JSON summary, the separate detailed-warning RSS
 * independently confirms the same warning, and the evidence is still fresh.
 *
 * It never infers a cancellation from disappearance. Recovery events are only
 * created for positively confirmed active warnings.
 */

const RECOVERY_FAILOVER_CONFIG = Object.freeze({
  triggerFunction: 'checkSourceGapRecoveryFailover',
  enabledPropertyKey: 'HKO_SOURCE_GAP_FAILOVER_ENABLED_V1',
  sentStatePropertyKey: 'HKO_SOURCE_GAP_FAILOVER_SENT_V1',
  statusPropertyKey: 'HKO_SOURCE_GAP_FAILOVER_STATUS_V1',
  intervalMinutes: 1,
  maxEvidenceAgeMs: 3 * 60 * 1000,
  maxSourceAgeMs: 3 * 60 * 1000,
  maxJournalBodyChars: 45000,
  schemaVersion: 1,
});

/**
 * Enables failover and installs exactly one one-minute trigger.
 * Running this while the normal source state is MATCH is safe and emits nothing.
 */
function setupSourceGapRecoveryFailover() {
  assertRecoveryFailoverDependencies_();
  const properties = PropertiesService.getScriptProperties();
  properties.setProperty(RECOVERY_FAILOVER_CONFIG.enabledPropertyKey, 'true');

  ScriptApp.getProjectTriggers()
    .filter(function (trigger) {
      return trigger.getHandlerFunction() === RECOVERY_FAILOVER_CONFIG.triggerFunction;
    })
    .forEach(function (trigger) {
      ScriptApp.deleteTrigger(trigger);
    });

  ScriptApp.newTrigger(RECOVERY_FAILOVER_CONFIG.triggerFunction)
    .timeBased()
    .everyMinutes(RECOVERY_FAILOVER_CONFIG.intervalMinutes)
    .create();

  return checkSourceGapRecoveryFailover();
}

function disableSourceGapRecoveryFailover() {
  const properties = PropertiesService.getScriptProperties();
  properties.setProperty(RECOVERY_FAILOVER_CONFIG.enabledPropertyKey, 'false');
  ScriptApp.getProjectTriggers()
    .filter(function (trigger) {
      return trigger.getHandlerFunction() === RECOVERY_FAILOVER_CONFIG.triggerFunction;
    })
    .forEach(function (trigger) {
      ScriptApp.deleteTrigger(trigger);
    });
}

function checkSourceGapRecoveryFailover() {
  const properties = PropertiesService.getScriptProperties();
  const nowEpochMs = Date.now();

  if (!recoveryFailoverEnabled_(properties)) {
    return writeRecoveryFailoverStatus_(properties, {
      schemaVersion: RECOVERY_FAILOVER_CONFIG.schemaVersion,
      checkedAtEpochMs: nowEpochMs,
      status: 'DISABLED',
      recoveredTokens: [],
      eventIds: [],
      primaryDetailTokens: [],
      primaryDetailError: '',
      pendingOutboxEvents: typeof readOutbox_ === 'function' ? readOutbox_(properties).length : 0,
    });
  }

  assertRecoveryFailoverDependencies_();
  const lock = LockService.getScriptLock();
  if (!lock.tryLock(20000)) {
    return writeRecoveryFailoverStatus_(properties, {
      schemaVersion: RECOVERY_FAILOVER_CONFIG.schemaVersion,
      checkedAtEpochMs: nowEpochMs,
      status: 'LOCK_BUSY',
      recoveredTokens: [],
      eventIds: [],
      primaryDetailTokens: [],
      primaryDetailError: '',
      pendingOutboxEvents: readOutbox_(properties).length,
    });
  }

  try {
    const source = readSourceCrossCheckForFailover_(properties);
    let sentState = readRecoveryFailoverSentState_(properties);
    sentState = pruneRecoveryFailoverSentState_(sentState, source);
    writeRecoveryFailoverSentState_(properties, sentState);

    const evidence = readSourceGapEvidenceForFailover_(properties);
    const eligibility = recoveryFailoverEligibility_(source, evidence, nowEpochMs);
    if (!eligibility.eligible) {
      return writeRecoveryFailoverStatus_(properties, {
        schemaVersion: RECOVERY_FAILOVER_CONFIG.schemaVersion,
        checkedAtEpochMs: nowEpochMs,
        status: eligibility.status,
        recoveredTokens: [],
        eventIds: [],
        primaryDetailTokens: [],
        primaryDetailError: '',
        pendingOutboxEvents: readOutbox_(properties).length,
      });
    }

    const primaryDetail = fetchPrimaryDetailTokensForFailover_();
    const uncoveredTokens = eligibility.tokens.filter(function (token) {
      return primaryDetail.tokens.indexOf(token) < 0;
    });

    if (uncoveredTokens.length === 0) {
      return writeRecoveryFailoverStatus_(properties, {
        schemaVersion: RECOVERY_FAILOVER_CONFIG.schemaVersion,
        checkedAtEpochMs: nowEpochMs,
        status: 'PRIMARY_DETAIL_COVERED',
        recoveredTokens: [],
        eventIds: [],
        primaryDetailTokens: primaryDetail.tokens,
        primaryDetailError: primaryDetail.error,
        pendingOutboxEvents: readOutbox_(properties).length,
      });
    }

    const detailResponse = UrlFetchApp.fetch(SOURCE_GAP_RECOVERY_CONFIG.detailedWarningRssUrl, {
      method: 'get',
      headers: {
        Accept: 'application/rss+xml, application/xml, text/xml, */*',
        'Cache-Control': 'no-cache',
      },
      muteHttpExceptions: true,
    });
    const detailVisible = parseDetailedWarningRss_(detailResponse);
    const detailDigest = digest_(detailVisible);
    if (detailDigest !== String(evidence.detailDigest || '')) {
      return writeRecoveryFailoverStatus_(properties, {
        schemaVersion: RECOVERY_FAILOVER_CONFIG.schemaVersion,
        checkedAtEpochMs: nowEpochMs,
        status: 'EVIDENCE_CHANGED',
        recoveredTokens: [],
        eventIds: [],
        primaryDetailTokens: primaryDetail.tokens,
        primaryDetailError: primaryDetail.error,
        pendingOutboxEvents: readOutbox_(properties).length,
      });
    }

    const currentDetailTokens = warningTokensFromText_(detailVisible);
    const confirmedNow = uncoveredTokens.filter(function (token) {
      return currentDetailTokens.indexOf(token) >= 0;
    });
    if (confirmedNow.length === 0) {
      return writeRecoveryFailoverStatus_(properties, {
        schemaVersion: RECOVERY_FAILOVER_CONFIG.schemaVersion,
        checkedAtEpochMs: nowEpochMs,
        status: 'DETAIL_NO_LONGER_CONFIRMS',
        recoveredTokens: [],
        eventIds: [],
        primaryDetailTokens: primaryDetail.tokens,
        primaryDetailError: primaryDetail.error,
        pendingOutboxEvents: readOutbox_(properties).length,
      });
    }

    assertConfiguration_();
    const rawXml = String(detailResponse.getContentText('UTF-8') || '');
    const sheet = ensureJournalSheet_(properties);
    const queueEvents = [];
    const recoveredTokens = [];
    const eventIds = [];

    confirmedNow.forEach(function (token) {
      const extracted = extractRecoveryDetailForToken_(rawXml, detailVisible, token);
      const body = extracted.body || detailVisible;
      if (!body || body.length > RECOVERY_FAILOVER_CONFIG.maxJournalBodyChars) {
        throw new Error('Recovery RSS body is empty or exceeds the journal safety limit for ' + token);
      }

      const metadata = recoveryMetadataForToken_(token, [extracted.titleText, body].join('\n'));
      const eventId = recoveryFailoverEventId_(token, detailDigest);
      const prior = sentState.tokens[token];
      if (prior && prior.eventId === eventId) return;

      let event = findJournalEvent_(sheet, eventId);
      if (!event) {
        event = buildRecoveryJournalEvent_(
          token,
          metadata,
          body,
          extracted.sourceTime,
          eventId,
          nowEpochMs,
          Math.max(1, sheet.getLastRow()),
        );
        sheet.appendRow([
          event.journalCursor,
          event.eventId,
          JSON.stringify(event),
          nowEpochMs,
        ]);
        SpreadsheetApp.flush();
      }

      queueEvents.push(event);
      recoveredTokens.push(token);
      eventIds.push(event.eventId);
      sentState.tokens[token] = {
        token: token,
        eventId: event.eventId,
        detailDigest: detailDigest,
        recoveredAtEpochMs: nowEpochMs,
      };
    });

    if (queueEvents.length === 0) {
      return writeRecoveryFailoverStatus_(properties, {
        schemaVersion: RECOVERY_FAILOVER_CONFIG.schemaVersion,
        checkedAtEpochMs: nowEpochMs,
        status: 'ALREADY_RECOVERED',
        recoveredTokens: [],
        eventIds: [],
        primaryDetailTokens: primaryDetail.tokens,
        primaryDetailError: primaryDetail.error,
        pendingOutboxEvents: readOutbox_(properties).length,
      });
    }

    const outbox = enqueueJournalEvents_(readOutbox_(properties), queueEvents, nowEpochMs);
    writeOutbox_(properties, outbox);
    writeRecoveryFailoverSentState_(properties, sentState);

    const flush = flushJournalOutbox_(properties, Date.now());
    if (typeof notificationPipelineMarkFlush_ === 'function') {
      notificationPipelineMarkFlush_(properties, Date.now(), flush.pending, flush.failed);
    }
    if (typeof seedPipelineJournalCursorFromSheet_ === 'function') {
      seedPipelineJournalCursorFromSheet_();
    }
    if (typeof refreshNotificationPipelineHealth_ === 'function') {
      refreshNotificationPipelineHealth_(properties, Date.now());
    }

    const status = writeRecoveryFailoverStatus_(properties, {
      schemaVersion: RECOVERY_FAILOVER_CONFIG.schemaVersion,
      checkedAtEpochMs: nowEpochMs,
      status: flush.failed > 0 ? 'RECOVERED_PENDING_FCM' : 'RECOVERED',
      recoveredTokens: recoveredTokens,
      eventIds: eventIds,
      primaryDetailTokens: primaryDetail.tokens,
      primaryDetailError: primaryDetail.error,
      pendingOutboxEvents: flush.pending,
    });

    if (flush.failed > 0) {
      throw new Error(flush.failed + ' recovery FCM wake-up attempt(s) failed and remain durable in the outbox.');
    }
    return status;
  } finally {
    lock.releaseLock();
  }
}

function recoveryFailoverEligibility_(source, evidence, nowEpochMs) {
  const now = Number(nowEpochMs || Date.now());
  if (!source || !evidence) return { eligible: false, status: 'EVIDENCE_UNAVAILABLE', tokens: [] };
  const sourceStatus = String(source.status || '');
  if (sourceStatus !== 'SECONDARY_ONLY' && sourceStatus !== 'DIVERGED') {
    return { eligible: false, status: 'IDLE', tokens: [] };
  }
  if (String(evidence.status || '') !== 'DETAIL_CONFIRMED') {
    return { eligible: false, status: 'DETAIL_NOT_CONFIRMED', tokens: [] };
  }
  const sourceCheckedAt = Number(source.checkedAtEpochMs || 0);
  const evidenceCheckedAt = Number(evidence.checkedAtEpochMs || 0);
  if (
    sourceCheckedAt <= 0 || evidenceCheckedAt <= 0 ||
    now - sourceCheckedAt > RECOVERY_FAILOVER_CONFIG.maxSourceAgeMs ||
    now - evidenceCheckedAt > RECOVERY_FAILOVER_CONFIG.maxEvidenceAgeMs
  ) {
    return { eligible: false, status: 'EVIDENCE_STALE', tokens: [] };
  }
  if (Number(source.consecutiveSecondaryOnly || 0) < 2 || Number(evidence.sourceGapStreak || 0) < 2) {
    return { eligible: false, status: 'GAP_NOT_PERSISTENT', tokens: [] };
  }
  const current = Array.isArray(source.secondaryOnly) ? source.secondaryOnly.slice() : [];
  const confirmed = Array.isArray(evidence.confirmedTokens) ? evidence.confirmedTokens.slice() : [];
  const tokens = current.filter(function (token) { return confirmed.indexOf(token) >= 0; }).sort();
  if (tokens.length === 0 || !String(evidence.detailDigest || '')) {
    return { eligible: false, status: 'TOKEN_CONFIRMATION_MISSING', tokens: [] };
  }
  return { eligible: true, status: 'ELIGIBLE', tokens: tokens };
}

function fetchPrimaryDetailTokensForFailover_() {
  try {
    const request = hkoRequest_('warningInfo');
    const response = UrlFetchApp.fetch(request.url, {
      method: request.method || 'get',
      headers: request.headers || {},
      muteHttpExceptions: request.muteHttpExceptions !== false,
    });
    const payload = parseHkoResponse_(response);
    const details = Array.isArray(payload && payload.details) ? payload.details : [];
    const parts = [];
    details.forEach(function (detail) {
      const family = cleanInlineText_(detail.warningStatementCode || '');
      const code = cleanInlineText_(detail.subtype || family);
      parts.push(family);
      parts.push(code);
      if (typeof warningName_ === 'function') parts.push(warningName_(code || family));
      if (typeof detailText_ === 'function') parts.push(detailText_(detail));
    });
    return { tokens: warningTokensFromText_(parts.join('\n')), error: '' };
  } catch (error) {
    // Detailed JSON is a duplicate-suppression aid, not a prerequisite for
    // recovery. Two independent RSS sources still provide positive evidence.
    return {
      tokens: [],
      error: String(error && error.message ? error.message : error).slice(0, 300),
    };
  }
}

function extractRecoveryDetailForToken_(rawXml, fullVisibleText, token) {
  const items = String(rawXml || '').match(/<item\b[\s\S]*?<\/item>/gi) || [];
  for (let index = 0; index < items.length; index += 1) {
    const titleText = failoverVisibleText_(extractRecoveryTag_(items[index], 'title'));
    const body = failoverVisibleText_(extractRecoveryTag_(items[index], 'description'));
    const itemVisible = [titleText, body].filter(Boolean).join('\n');
    if (warningTokensFromText_(itemVisible).indexOf(token) < 0) continue;
    return {
      titleText: titleText,
      body: body || itemVisible,
      sourceTime: failoverVisibleText_(extractRecoveryTag_(items[index], 'pubDate')),
    };
  }
  return { titleText: '', body: fullVisibleText, sourceTime: '' };
}

function extractRecoveryTag_(xml, tagName) {
  const pattern = new RegExp('<' + tagName + '[^>]*>([\\s\\S]*?)</' + tagName + '>', 'i');
  const match = pattern.exec(String(xml || ''));
  return match ? match[1] : '';
}

function failoverVisibleText_(value) {
  if (typeof rssVisibleText_ === 'function') return rssVisibleText_(value);
  if (typeof recoveryVisibleText_ === 'function') return recoveryVisibleText_(value);
  return String(value || '')
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

function recoveryMetadataForToken_(token, text) {
  const value = String(text || '');
  const map = {
    'TC:1': { code: 'TC1', title: '一號戒備信號' },
    'TC:3': { code: 'TC3', title: '三號強風信號' },
    'TC:9': { code: 'TC9', title: '九號烈風或暴風風力增強信號' },
    'TC:10': { code: 'TC10', title: '十號颶風信號' },
    'RAIN:AMBER': { code: 'WRAINA', title: '黃色暴雨警告信號' },
    'RAIN:RED': { code: 'WRAINR', title: '紅色暴雨警告信號' },
    'RAIN:BLACK': { code: 'WRAINB', title: '黑色暴雨警告信號' },
    THUNDERSTORM: { code: 'WTS', title: '雷暴警告' },
    HOT: { code: 'WHOT', title: '酷熱天氣警告' },
    COLD: { code: 'WCOLD', title: '寒冷天氣警告' },
    FROST: { code: 'WFROST', title: '霜凍警告' },
    LANDSLIP: { code: 'WL', title: '山泥傾瀉警告' },
    NT_FLOOD: { code: 'WFNTSA', title: '新界北部水浸特別報告' },
    MONSOON: { code: 'WMSGNL', title: '強烈季候風信號' },
    TSUNAMI: { code: 'WTMW', title: '海嘯警告' },
  };

  let result;
  if (token === 'TC:8') {
    if (/八號東北烈風或暴風信號/.test(value)) result = { code: 'TC8NE', title: '八號東北烈風或暴風信號' };
    else if (/八號西北烈風或暴風信號/.test(value)) result = { code: 'TC8NW', title: '八號西北烈風或暴風信號' };
    else if (/八號東南烈風或暴風信號/.test(value)) result = { code: 'TC8SE', title: '八號東南烈風或暴風信號' };
    else if (/八號西南烈風或暴風信號/.test(value)) result = { code: 'TC8SW', title: '八號西南烈風或暴風信號' };
    else result = { code: 'TC8', title: '八號烈風或暴風信號' };
  } else if (token === 'FIRE') {
    if (/紅色火災危險警告/.test(value)) result = { code: 'WFIRER', title: '紅色火災危險警告' };
    else result = { code: 'WFIREY', title: '黃色火災危險警告' };
  } else {
    result = map[token] || { code: 'WARNING', title: '天氣警告' };
  }

  const severity = typeof severity_ === 'function'
    ? severity_(result.code, false)
    : recoveryFallbackSeverity_(result.code);
  return { code: result.code, title: result.title, severity: severity };
}

function recoveryFallbackSeverity_(code) {
  if (/^TC(8|9|10)/.test(code) || code === 'WRAINR' || code === 'WRAINB' || code === 'WTMW') return 'URGENT';
  if (code === 'TC3' || code === 'WRAINA' || code === 'WTS' || code === 'WL' || code === 'WFIRER') return 'WARNING';
  return 'ADVISORY';
}

function buildRecoveryJournalEvent_(token, metadata, body, sourceTime, eventId, nowEpochMs, cursor) {
  const channel = typeof channelFor_ === 'function'
    ? channelFor_(metadata.severity, false)
    : metadata.severity === 'URGENT'
      ? 'weather_alert_urgent'
      : 'weather_alert_general';
  return {
    eventId: eventId,
    title: metadata.title,
    body: body,
    channel: channel,
    target: 'weathermetro://current/alerts' +
      '?alertId=' + encodeURIComponent('recovery:' + token) +
      '&code=' + encodeURIComponent(metadata.code) +
      '&kind=RECOVERY',
    alertId: 'recovery:' + token,
    alertCode: metadata.code,
    eventKind: 'RECOVERY',
    sourceType: 'RSS_RECOVERY',
    sourceTime: sourceTime || '',
    sentAtEpochMillis: Number(nowEpochMs),
    journalCursor: Number(cursor),
  };
}

function recoveryFailoverEventId_(token, detailDigest) {
  return 'hko:recovery:' + digest_([token, detailDigest].join('|'));
}

function recoveryFailoverEnabled_(properties) {
  return String(properties.getProperty(RECOVERY_FAILOVER_CONFIG.enabledPropertyKey) || '').toLowerCase() === 'true';
}

function readSourceCrossCheckForFailover_(properties) {
  if (typeof readWarningSourceCrossCheck_ === 'function') return readWarningSourceCrossCheck_(properties);
  const raw = properties.getProperty('HKO_WARNING_SOURCE_CROSSCHECK_HEALTH_V1');
  return recoveryFailoverSafeParse_(raw, null);
}

function readSourceGapEvidenceForFailover_(properties) {
  if (typeof readSourceGapRecoveryEvidence_ === 'function') return readSourceGapRecoveryEvidence_(properties);
  const raw = properties.getProperty('HKO_SOURCE_GAP_RECOVERY_EVIDENCE_V1');
  return recoveryFailoverSafeParse_(raw, null);
}

function readRecoveryFailoverSentState_(properties) {
  const parsed = recoveryFailoverSafeParse_(
    properties.getProperty(RECOVERY_FAILOVER_CONFIG.sentStatePropertyKey),
    null,
  );
  if (!parsed || typeof parsed !== 'object' || !parsed.tokens || typeof parsed.tokens !== 'object') {
    return { schemaVersion: RECOVERY_FAILOVER_CONFIG.schemaVersion, tokens: {} };
  }
  return { schemaVersion: RECOVERY_FAILOVER_CONFIG.schemaVersion, tokens: Object.assign({}, parsed.tokens) };
}

function writeRecoveryFailoverSentState_(properties, state) {
  properties.setProperty(
    RECOVERY_FAILOVER_CONFIG.sentStatePropertyKey,
    JSON.stringify({ schemaVersion: RECOVERY_FAILOVER_CONFIG.schemaVersion, tokens: state.tokens || {} }),
  );
}

function pruneRecoveryFailoverSentState_(state, source) {
  const next = { schemaVersion: RECOVERY_FAILOVER_CONFIG.schemaVersion, tokens: Object.assign({}, state.tokens || {}) };
  if (!source || source.primaryOk !== true || source.secondaryOk !== true) return next;
  const active = {};
  (Array.isArray(source.primaryTokens) ? source.primaryTokens : []).forEach(function (token) { active[token] = true; });
  (Array.isArray(source.secondaryTokens) ? source.secondaryTokens : []).forEach(function (token) { active[token] = true; });
  Object.keys(next.tokens).forEach(function (token) {
    if (!active[token]) delete next.tokens[token];
  });
  return next;
}

function writeRecoveryFailoverStatus_(properties, status) {
  const compact = Object.assign({}, status);
  properties.setProperty(RECOVERY_FAILOVER_CONFIG.statusPropertyKey, JSON.stringify(compact));
  console.log('HKO recovery failover: ' + JSON.stringify(compact));
  return compact;
}

function readRecoveryFailoverStatus_(properties) {
  return recoveryFailoverSafeParse_(
    properties.getProperty(RECOVERY_FAILOVER_CONFIG.statusPropertyKey),
    null,
  );
}

function recoveryFailoverSafeParse_(value, fallback) {
  try {
    const parsed = JSON.parse(value);
    return parsed === undefined ? fallback : parsed;
  } catch (_error) {
    return fallback;
  }
}

function assertRecoveryFailoverDependencies_() {
  const required = [
    ['SOURCE_GAP_RECOVERY_CONFIG', typeof SOURCE_GAP_RECOVERY_CONFIG !== 'undefined'],
    ['parseDetailedWarningRss_', typeof parseDetailedWarningRss_ === 'function'],
    ['warningTokensFromText_', typeof warningTokensFromText_ === 'function'],
    ['ensureJournalSheet_', typeof ensureJournalSheet_ === 'function'],
    ['findJournalEvent_', typeof findJournalEvent_ === 'function'],
    ['enqueueJournalEvents_', typeof enqueueJournalEvents_ === 'function'],
    ['readOutbox_', typeof readOutbox_ === 'function'],
    ['writeOutbox_', typeof writeOutbox_ === 'function'],
    ['flushJournalOutbox_', typeof flushJournalOutbox_ === 'function'],
    ['digest_', typeof digest_ === 'function'],
  ];
  const missing = required.filter(function (entry) { return !entry[1]; }).map(function (entry) { return entry[0]; });
  if (missing.length > 0) throw new Error('Recovery failover dependency missing: ' + missing.join(', '));
}

/** Owner-facing verification. No HKO bodies or credentials are returned. */
function verifySourceGapRecoveryFailover() {
  const properties = PropertiesService.getScriptProperties();
  const sentState = readRecoveryFailoverSentState_(properties);
  const result = {
    enabled: recoveryFailoverEnabled_(properties),
    triggerCount: ScriptApp.getProjectTriggers().filter(function (trigger) {
      return trigger.getHandlerFunction() === RECOVERY_FAILOVER_CONFIG.triggerFunction;
    }).length,
    recoveredTokens: Object.keys(sentState.tokens).sort(),
    recoveredEvents: Object.keys(sentState.tokens).sort().map(function (token) {
      const item = sentState.tokens[token];
      return {
        token: token,
        eventId: item.eventId || '',
        detailDigest: item.detailDigest || '',
        recoveredAtEpochMs: Number(item.recoveredAtEpochMs || 0),
      };
    }),
    status: readRecoveryFailoverStatus_(properties),
  };
  console.log(JSON.stringify(result));
  return result;
}

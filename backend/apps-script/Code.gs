/**
 * Weather Metro server-side alert monitor.
 *
 * Required Script Properties (never commit their values):
 *   FIREBASE_PROJECT_ID
 *   FIREBASE_CLIENT_EMAIL
 *   FIREBASE_PRIVATE_KEY
 */

const CONFIG = Object.freeze({
  topic: 'hko_alerts',
  stateIndexKey: 'HKO_PUBLICATION_STATE_INDEX_V6',
  stateItemPrefix: 'HKO_PUBLICATION_STATE_ITEM_V6_',
  legacyStateKey: 'HKO_ALERT_STATE_V4',
  olderLegacyStateKey: 'HKO_ALERT_STATE_V3',
  legacyStateIndexKey: 'HKO_ALERT_STATE_INDEX_V5',
  legacyStateItemPrefix: 'HKO_ALERT_STATE_ITEM_V5_',
  outboxKey: 'HKO_ALERT_OUTBOX_V1',
  outboxIndexKey: 'HKO_ALERT_OUTBOX_INDEX_V2',
  outboxEventPrefix: 'HKO_ALERT_OUTBOX_EVENT_V2_',
  triggerFunction: 'checkWeatherUpdates',
  hkoBase: 'https://data.weather.gov.hk/weatherAPI/opendata/weather.php',
  tokenUrl: 'https://oauth2.googleapis.com/token',
  fcmScope: 'https://www.googleapis.com/auth/firebase.messaging',
  androidPackage: 'com.weather.metro',
  maxTitleBytes: 180,
  maxBodyBytes: 900,
  maxSystemNotificationBodyBytes: 300,
  maxStateBodyBytes: 6000,
  maxOutboxEvents: 100,
});

/** Installs exactly one one-minute trigger. Run this once from the Apps Script editor. */
function installOneMinuteTrigger() {
  ScriptApp.getProjectTriggers()
    .filter(function (trigger) {
      return trigger.getHandlerFunction() === CONFIG.triggerFunction;
    })
    .forEach(function (trigger) {
      ScriptApp.deleteTrigger(trigger);
    });

  ScriptApp.newTrigger(CONFIG.triggerFunction)
    .timeBased()
    .everyMinutes(1)
    .create();
}

/** Backwards-compatible setup entry point retained for existing deployments. */
function installFiveMinuteTrigger() {
  installOneMinuteTrigger();
}

/** Polls official HKO publication endpoints and sends each newly observed publication once. */
function checkWeatherUpdates() {
  const lock = LockService.getScriptLock();
  if (!lock.tryLock(20000)) {
    console.log('A previous alert check is still running; this execution was skipped.');
    return;
  }

  try {
    assertConfiguration_();
    const properties = PropertiesService.getScriptProperties();
    // Retry accepted work before contacting HKO, so an HKO outage cannot block
    // recovery from an unrelated earlier FCM outage.
    const retryResult = flushOutbox_(properties, Date.now());
    const responses = UrlFetchApp.fetchAll([
      hkoRequest_('warnsum'),
      hkoRequest_('warningInfo'),
      hkoRequest_('swt'),
    ]);
    const payloads = responses.map(parseHkoResponse_);
    const current = normaliseState_(payloads[0], payloads[1], payloads[2]);
    const previous = readState_(properties);
    const events = previous === null ? initialEvents_(current) : diffStates_(previous, current);
    const outbox = enqueueEvents_(readOutbox_(properties), events, Date.now());
    // Persist the outbox first. If the state write fails, the next execution can
    // safely enqueue the same deterministic publication IDs without duplicating events.
    writeOutbox_(properties, outbox);
    writeState_(properties, current);

    const result = flushOutbox_(properties, Date.now());
    const sent = retryResult.sent + result.sent;
    const failed = retryResult.failed + result.failed;
    console.log(
      'Detected ' + events.length + ' HKO publication(s); sent ' + sent +
      ', pending ' + result.pending + '.',
    );
    if (failed > 0) {
      throw new Error(failed + ' queued FCM send attempt(s) failed and will be retried.');
    }
  } finally {
    lock.releaseLock();
  }
}

/** Clears saved publication state. The next check reissues every publication currently visible. */
function resetAlertBaseline() {
  const properties = PropertiesService.getScriptProperties();
  clearState_(properties);
}

/** Sends a harmless connectivity check to subscribed test devices. */
function sendTestNotification() {
  assertConfiguration_();
  sendFcm_({
    title: 'Weather Metro',
    body: 'FCM HTTP v1 connection is working.',
    channel: 'weather_service_status',
    eventId: 'test:' + Date.now(),
    target: 'weathermetro://current',
  });
}

function hkoRequest_(dataType) {
  return {
    url: CONFIG.hkoBase + '?dataType=' + encodeURIComponent(dataType) + '&lang=tc',
    method: 'get',
    headers: { Accept: 'application/json' },
    muteHttpExceptions: true,
  };
}

function parseHkoResponse_(response) {
  const code = response.getResponseCode();
  if (code < 200 || code >= 300) {
    throw new Error('HKO returned HTTP ' + code);
  }
  return JSON.parse(response.getContentText('UTF-8'));
}

/**
 * Converts the three HKO snapshots into source publications rather than inferred state changes.
 * Official warnsum actionCode values are retained verbatim (for example ISSUE, REISSUE,
 * CANCEL, EXTEND, UPDATE). warningInfo-only statements and Special Weather Tips are also
 * represented as publications, but their disappearance never creates a synthetic cancellation.
 */
function normaliseState_(summary, detailPayload, tipPayload) {
  const details = Array.isArray(detailPayload && detailPayload.details) ? detailPayload.details : [];
  const state = {};
  const matchedDetails = {};

  Object.keys(summary || {}).sort().forEach(function (family) {
    const row = summary[family] || {};
    const code = cleanInlineText_(row.code || family) || family;
    const actionCode = normaliseActionCode_(row.actionCode || 'ISSUE');
    const match = findDetailForSummary_(details, family, code, actionCode);
    const detail = match ? match.detail : {};
    if (match) matchedDetails[match.index] = true;
    const body = detailText_(detail);
    const title = cleanInlineText_(row.type || row.name || warningName_(code));
    const issueTime = String(row.issueTime || detail.issueTime || '');
    const expireTime = String(row.expireTime || detail.expireTime || '');
    const updateTime = String(row.updateTime || detail.updateTime || '');
    const sourceKey = 'warning:' + (family || code);
    const item = publication_({
      sourceType: 'WARNING',
      sourceKey: sourceKey,
      family: family,
      code: code,
      actionCode: actionCode,
      title: title,
      body: body || title,
      issueTime: issueTime,
      expireTime: expireTime,
      updateTime: updateTime,
      severity: severity_(code, false),
      isTip: false,
    });
    state[item.id] = item;
  });

  // warningInfo can contain official statements that do not have a warnsum row,
  // notably WTCPRE8. Preserve every unmatched non-empty official detail rather
  // than silently dropping a future detail-only statement.
  details.forEach(function (detail, index) {
    if (matchedDetails[index]) return;
    const body = detailText_(detail);
    if (!body) return;
    const family = cleanInlineText_(detail.warningStatementCode || 'WARNING_INFO');
    const code = cleanInlineText_(detail.subtype || family) || family;
    const title = detailTitle_(family, code);
    const updateTime = String(detail.updateTime || '');
    const sourceKey = 'statement:' + family + ':' + code;
    const item = publication_({
      sourceType: 'STATEMENT',
      sourceKey: sourceKey,
      family: family,
      code: code,
      actionCode: 'STATEMENT',
      title: title,
      body: body,
      issueTime: String(detail.issueTime || ''),
      expireTime: String(detail.expireTime || ''),
      updateTime: updateTime,
      severity: severity_(code, false),
      isTip: false,
    });
    state[item.id] = item;
  });

  const tips = Array.isArray(tipPayload && tipPayload.swt) ? tipPayload.swt : [];
  tips.forEach(function (tip) {
    const body = preserveSourceText_(tip.desc || '');
    if (!body) return;
    const updateTime = String(tip.updateTime || '');
    const sourceKey = 'tip:' + digest_([updateTime, body].join('|'));
    const item = publication_({
      sourceType: 'SWT',
      sourceKey: sourceKey,
      family: 'SWT',
      code: 'SWT',
      actionCode: 'SWT',
      title: '特別天氣提示',
      body: body,
      issueTime: '',
      expireTime: '',
      updateTime: updateTime,
      severity: severity_(body, true),
      isTip: true,
    });
    state[item.id] = item;
  });

  return state;
}

function publication_(input) {
  const body = truncateUtf8_(input.body || input.title || '', CONFIG.maxStateBodyBytes);
  const signature = [
    input.sourceType || '',
    input.sourceKey || '',
    input.family || '',
    input.code || '',
    input.actionCode || '',
    input.issueTime || '',
    input.expireTime || '',
    input.updateTime || '',
    input.title || '',
    body,
  ].join('|');
  return {
    id: 'publication:' + digest_(signature),
    sourceType: input.sourceType || 'WARNING',
    sourceKey: input.sourceKey || '',
    family: input.family || '',
    code: input.code || '',
    actionCode: input.actionCode || 'STATEMENT',
    title: input.title || '香港天文台',
    body: body,
    issueTime: input.issueTime || '',
    expireTime: input.expireTime || '',
    updateTime: input.updateTime || '',
    updatedAt: input.updateTime || input.issueTime || input.expireTime || '',
    severity: input.severity || 'ADVISORY',
    isTip: Boolean(input.isTip),
    fingerprint: digest_(signature),
  };
}

function diffStates_(previous, current) {
  const events = [];
  Object.keys(current).sort().forEach(function (id) {
    if (!previous[id]) {
      events.push({ kind: current[id].actionCode || 'STATEMENT', item: current[id] });
    }
  });
  return events;
}

function initialEvents_(current) {
  return Object.keys(current).sort().map(function (id) {
    return { kind: current[id].actionCode || 'STATEMENT', item: current[id] };
  });
}

function findDetailForSummary_(details, family, code, actionCode) {
  const familyCode = familyForCode_(code);
  const candidates = [];
  details.forEach(function (detail, index) {
    const statement = cleanInlineText_(detail.warningStatementCode || '');
    const subtype = cleanInlineText_(detail.subtype || '');
    if (subtype === code || statement === family || statement === familyCode) {
      candidates.push({ detail: detail, index: index, statement: statement, subtype: subtype });
    }
  });
  if (candidates.length === 0) return null;
  if (actionCode === 'CANCEL') {
    const cancellation = candidates.find(function (candidate) {
      return candidate.subtype.toUpperCase() === 'CANCEL';
    });
    if (cancellation) return cancellation;
  }
  const exactSubtype = candidates.find(function (candidate) { return candidate.subtype === code; });
  return exactSubtype || candidates[0];
}

function detailText_(detail) {
  const contents = Array.isArray(detail && detail.contents) ? detail.contents : [];
  return contents
    .map(preserveSourceText_)
    .filter(Boolean)
    .join('\n\n');
}

function detailTitle_(family, code) {
  if (family === 'WTCPRE8') return '八號信號預警特別報告';
  return warningName_(code || family);
}

function normaliseActionCode_(value) {
  return cleanInlineText_(value || 'ISSUE').toUpperCase() || 'ISSUE';
}

function readState_(properties) {
  const indexText = properties.getProperty(CONFIG.stateIndexKey);
  // V6 deliberately starts a new source-publication baseline. On the first run
  // after upgrade, every HKO publication still visible is emitted once rather
  // than risking a missed transition during an inexact V5 migration.
  if (indexText === null) return null;
  const ids = safeParse_(indexText, []);
  if (!Array.isArray(ids)) return {};
  const state = {};
  ids.forEach(function (id) {
    const value = properties.getProperty(statePropertyKey_(id));
    if (value === null) throw new Error('Missing publication state property for ' + id);
    const item = safeParse_(value, null);
    if (!item) throw new Error('Invalid publication state property for ' + id);
    state[id] = item;
  });
  return state;
}

function writeState_(properties, state) {
  const previousIds = safeParse_(properties.getProperty(CONFIG.stateIndexKey), []);
  const nextIds = Object.keys(state).sort();
  const nextIdSet = {};
  const values = {};
  nextIds.forEach(function (id) {
    nextIdSet[id] = true;
    values[statePropertyKey_(id)] = JSON.stringify(state[id]);
  });
  values[CONFIG.stateIndexKey] = JSON.stringify(nextIds);
  properties.setProperties(values, false);
  (Array.isArray(previousIds) ? previousIds : []).forEach(function (id) {
    if (!nextIdSet[id]) properties.deleteProperty(statePropertyKey_(id));
  });
  clearLegacyState_(properties);
}

function clearState_(properties) {
  const ids = safeParse_(properties.getProperty(CONFIG.stateIndexKey), []);
  (Array.isArray(ids) ? ids : []).forEach(function (id) {
    properties.deleteProperty(statePropertyKey_(id));
  });
  properties.deleteProperty(CONFIG.stateIndexKey);
  clearLegacyState_(properties);
}

function clearLegacyState_(properties) {
  const legacyIds = safeParse_(properties.getProperty(CONFIG.legacyStateIndexKey), []);
  (Array.isArray(legacyIds) ? legacyIds : []).forEach(function (id) {
    properties.deleteProperty(CONFIG.legacyStateItemPrefix + digest_(id));
  });
  properties.deleteProperty(CONFIG.legacyStateIndexKey);
  properties.deleteProperty(CONFIG.legacyStateKey);
  properties.deleteProperty(CONFIG.olderLegacyStateKey);
}

function statePropertyKey_(id) {
  return CONFIG.stateItemPrefix + digest_(id);
}

function messageForEvent_(event, queuedAtEpochMs) {
  const item = event.item;
  const eventId = 'hko:' + digest_([event.kind, item.id].join('|'));
  const alertId = item.sourceKey || item.id;
  const target = 'weathermetro://current/alerts' +
    '?alertId=' + encodeURIComponent(alertId) +
    '&code=' + encodeURIComponent(item.code) +
    '&kind=' + encodeURIComponent(event.kind);
  const fullBody = String(item.body || item.title || '香港天文台天氣更新');
  const body = truncateUtf8_(fullBody, CONFIG.maxBodyBytes);
  return {
    title: truncateUtf8_(item.title, CONFIG.maxTitleBytes),
    body: body,
    bodyTruncated: body !== fullBody ? 'true' : 'false',
    channel: channelFor_(item.severity, item.isTip),
    eventId: eventId,
    alertId: alertId,
    alertCode: item.code,
    eventKind: event.kind,
    sourceType: item.sourceType || '',
    sourceTime: item.updatedAt || '',
    target: target,
    sentAtEpochMs: String(queuedAtEpochMs),
    schemaVersion: '3',
  };
}

function readOutbox_(properties) {
  const indexText = properties.getProperty(CONFIG.outboxIndexKey);
  if (indexText === null) {
    const legacy = safeParse_(properties.getProperty(CONFIG.outboxKey), []);
    return Array.isArray(legacy) ? legacy : [];
  }
  const ids = safeParse_(indexText, []);
  if (!Array.isArray(ids)) return [];
  return ids.map(function (id) {
    const value = properties.getProperty(CONFIG.outboxEventPrefix + id);
    if (value === null) throw new Error('Missing FCM outbox property for ' + id);
    const entry = safeParse_(value, null);
    if (!entry) throw new Error('Invalid FCM outbox property for ' + id);
    return entry;
  });
}

function writeOutbox_(properties, outbox) {
  const previousIds = safeParse_(properties.getProperty(CONFIG.outboxIndexKey), []);
  const nextIds = outbox.map(function (entry) { return entry.id; });
  const nextIdSet = {};
  const values = {};
  outbox.forEach(function (entry) {
    nextIdSet[entry.id] = true;
    values[CONFIG.outboxEventPrefix + entry.id] = JSON.stringify(entry);
  });
  values[CONFIG.outboxIndexKey] = JSON.stringify(nextIds);
  properties.setProperties(values, false);
  (Array.isArray(previousIds) ? previousIds : []).forEach(function (id) {
    if (!nextIdSet[id]) properties.deleteProperty(CONFIG.outboxEventPrefix + id);
  });
  properties.deleteProperty(CONFIG.outboxKey);
}

function enqueueEvents_(outbox, events, nowEpochMs) {
  const queued = outbox.slice();
  const knownIds = {};
  queued.forEach(function (entry) { knownIds[entry.id] = true; });
  events.forEach(function (event) {
    const message = messageForEvent_(event, nowEpochMs);
    if (knownIds[message.eventId]) return;
    queued.push({
      id: message.eventId,
      message: message,
      attempts: 0,
      queuedAtEpochMs: nowEpochMs,
      nextAttemptEpochMs: 0,
      lastError: '',
    });
    knownIds[message.eventId] = true;
  });
  if (queued.length > CONFIG.maxOutboxEvents) {
    throw new Error('FCM outbox capacity exceeded; refusing to discard weather events.');
  }
  return queued;
}

function flushOutbox_(properties, nowEpochMs) {
  const pending = readOutbox_(properties);
  const remaining = [];
  let sent = 0;
  let failed = 0;
  pending.forEach(function (entry) {
    if (Number(entry.nextAttemptEpochMs || 0) > nowEpochMs) {
      remaining.push(entry);
      return;
    }
    try {
      sendFcm_(entry.message);
      sent += 1;
    } catch (error) {
      const attempts = Number(entry.attempts || 0) + 1;
      entry.attempts = attempts;
      entry.lastError = String(error && error.message ? error.message : error).slice(0, 500);
      entry.nextAttemptEpochMs = nowEpochMs + retryDelayMillis_(attempts);
      remaining.push(entry);
      failed += 1;
      console.error('FCM outbox event ' + entry.id + ' failed: ' + entry.lastError);
    }
  });
  writeOutbox_(properties, remaining);
  return { sent: sent, failed: failed, pending: remaining.length };
}

function retryDelayMillis_(attempts) {
  const exponential = Math.min(60 * 60 * 1000, 60 * 1000 * Math.pow(2, Math.min(attempts - 1, 6)));
  return exponential + Math.floor(Math.random() * 30000);
}

function sendFcm_(message) {
  const props = PropertiesService.getScriptProperties();
  const projectId = props.getProperty('FIREBASE_PROJECT_ID');
  const endpoint = 'https://fcm.googleapis.com/v1/projects/' + encodeURIComponent(projectId) + '/messages:send';
  const payload = buildLegacyFcmPayload_(message);
  const response = UrlFetchApp.fetch(endpoint, {
    method: 'post',
    contentType: 'application/json',
    headers: { Authorization: 'Bearer ' + accessToken_() },
    payload: JSON.stringify(payload),
    muteHttpExceptions: true,
  });
  if (response.getResponseCode() < 200 || response.getResponseCode() >= 300) {
    throw new Error('FCM HTTP ' + response.getResponseCode() + ': ' + response.getContentText());
  }
}

/**
 * Builds a notification + data message rather than a data-only wake-up.
 *
 * Android can render the notification payload from Google Play services while
 * Weather Metro is backgrounded, without starting the app process first. The
 * same eventId is used as the system notification tag and by the Android
 * publisher, so foreground delivery and later journal hydration update one
 * visible notification instead of creating duplicates.
 */
function buildFcmPayload_(message, data) {
  const channel = message.channel || 'weather_alert_general';
  const eventId = message.eventId || '';
  const androidNotification = {
    channelId: channel,
    icon: 'ic_notification',
  };
  if (eventId) androidNotification.tag = eventId;

  return {
    message: {
      topic: CONFIG.topic,
      notification: {
        title: message.title || '香港天文台',
        // The topic limit is 2,048 bytes. Keep a useful system-tray excerpt
        // while the journal retains and later hydrates the complete body.
        body: truncateUtf8_(message.body || '', CONFIG.maxSystemNotificationBodyBytes),
      },
      data: data,
      android: {
        priority: channel === 'weather_service_status' ? 'NORMAL' : 'HIGH',
        ttl: '86400s',
        restrictedPackageName: CONFIG.androidPackage,
        notification: androidNotification,
      },
    },
  };
}

function buildLegacyFcmPayload_(message) {
  return buildFcmPayload_(message, {
    channel: message.channel,
    eventId: message.eventId,
    alertId: message.alertId || '',
    alertCode: message.alertCode || '',
    eventKind: message.eventKind || '',
    sourceType: message.sourceType || '',
    sourceTime: message.sourceTime || '',
    target: message.target,
    bodyTruncated: message.bodyTruncated || 'false',
    sentAtEpochMs: message.sentAtEpochMs || String(Date.now()),
    schemaVersion: message.schemaVersion || '3',
  });
}

function accessToken_() {
  const cache = CacheService.getScriptCache();
  const cached = cache.get('fcm_access_token');
  if (cached) return cached;

  const props = PropertiesService.getScriptProperties();
  const clientEmail = props.getProperty('FIREBASE_CLIENT_EMAIL');
  const privateKey = props.getProperty('FIREBASE_PRIVATE_KEY').replace(/\\n/g, '\n');
  const now = Math.floor(Date.now() / 1000);
  const header = base64WebSafe_(JSON.stringify({ alg: 'RS256', typ: 'JWT' }));
  const claim = base64WebSafe_(JSON.stringify({
    iss: clientEmail,
    scope: CONFIG.fcmScope,
    aud: CONFIG.tokenUrl,
    iat: now,
    exp: now + 3600,
  }));
  const unsigned = header + '.' + claim;
  const signature = Utilities.computeRsaSha256Signature(unsigned, privateKey);
  const assertion = unsigned + '.' + Utilities.base64EncodeWebSafe(signature).replace(/=+$/, '');
  const response = UrlFetchApp.fetch(CONFIG.tokenUrl, {
    method: 'post',
    payload: {
      grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer',
      assertion: assertion,
    },
    muteHttpExceptions: true,
  });
  if (response.getResponseCode() < 200 || response.getResponseCode() >= 300) {
    throw new Error('OAuth token HTTP ' + response.getResponseCode() + ': ' + response.getContentText());
  }
  const token = JSON.parse(response.getContentText()).access_token;
  cache.put('fcm_access_token', token, 3300);
  return token;
}

function assertConfiguration_() {
  const props = PropertiesService.getScriptProperties();
  ['FIREBASE_PROJECT_ID', 'FIREBASE_CLIENT_EMAIL', 'FIREBASE_PRIVATE_KEY'].forEach(function (key) {
    if (!props.getProperty(key)) throw new Error('Missing Script Property: ' + key);
  });
}

function safeParse_(text, fallback) {
  try { return JSON.parse(text); } catch (error) { return fallback; }
}

function cleanInlineText_(value) {
  return String(value || '').replace(/\s+/g, ' ').trim();
}

function preserveSourceText_(value) {
  return String(value || '')
    .replace(/\r\n?/g, '\n')
    .replace(/[\t\f\v ]+$/gm, '')
    .trim();
}

function truncateUtf8_(value, maxBytes) {
  const text = String(value || '').trim();
  if (Utilities.newBlob(text).getBytes().length <= maxBytes) return text;
  const suffix = '…';
  let low = 0;
  let high = text.length;
  while (low < high) {
    const middle = Math.ceil((low + high) / 2);
    const candidate = text.slice(0, middle).replace(/[\uD800-\uDBFF]$/, '') + suffix;
    if (Utilities.newBlob(candidate).getBytes().length <= maxBytes) low = middle;
    else high = middle - 1;
  }
  return text.slice(0, low).replace(/[\uD800-\uDBFF]$/, '').trimEnd() + suffix;
}

function digest_(value) {
  const bytes = Utilities.computeDigest(Utilities.DigestAlgorithm.SHA_256, String(value), Utilities.Charset.UTF_8);
  return bytes.map(function (byte) {
    return ('0' + ((byte + 256) % 256).toString(16)).slice(-2);
  }).join('').slice(0, 24);
}

function base64WebSafe_(value) {
  return Utilities.base64EncodeWebSafe(value, Utilities.Charset.UTF_8).replace(/=+$/, '');
}

function channelFor_(severity, isTip) {
  if (severity === 'URGENT') return 'weather_alert_urgent';
  if (isTip) return 'weather_tips';
  return 'weather_alert_general';
}

function severity_(value, isTip) {
  if (isTip) {
    return /水浸|猛烈陣風|冰雹|水龍捲|山泥傾瀉/.test(value) ? 'WARNING' : 'TIP';
  }
  if (/^TC(8|9|10)/.test(value) || ['WRAINR', 'WRAINB', 'WTMW'].indexOf(value) >= 0) return 'URGENT';
  if (['WRAINA', 'WTS', 'TC3', 'WL', 'WFIRER'].indexOf(value) >= 0) return 'WARNING';
  return 'ADVISORY';
}

function familyForCode_(code) {
  if (code.indexOf('TC') === 0) return 'WTCSGNL';
  if (code.indexOf('WRAIN') === 0) return 'WRAIN';
  if (code.indexOf('WFIRE') === 0) return 'WFIRE';
  if (code === 'WFNW' || code === 'WFNTSA') return 'WFNTSA';
  if (code === 'WMSGN' || code === 'WMSGNL') return 'WMSGNL';
  return code;
}

function warningName_(code) {
  const names = {
    WTS: '雷暴警告', WRAINA: '黃色暴雨警告', WRAINR: '紅色暴雨警告', WRAINB: '黑色暴雨警告',
    TC1: '一號戒備信號', TC3: '三號強風信號', TC8NE: '八號東北烈風或暴風信號',
    TC8SE: '八號東南烈風或暴風信號', TC8NW: '八號西北烈風或暴風信號',
    TC8SW: '八號西南烈風或暴風信號', TC9: '九號烈風或暴風風力增強信號', TC10: '十號颶風信號',
    WHOT: '酷熱天氣警告', WCOLD: '寒冷天氣警告', WMSGNL: '強烈季候風信號',
    WL: '山泥傾瀉警告', WFROST: '霜凍警告', WFIREY: '黃色火災危險警告',
    WFIRER: '紅色火災危險警告', WTMW: '海嘯警告', WFNTSA: '新界北部水浸特別報告',
    WTCPRE8: '八號信號預警特別報告',
  };
  return names[code] || '天氣警告';
}

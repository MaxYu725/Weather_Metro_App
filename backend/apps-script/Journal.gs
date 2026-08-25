/**
 * Weather Metro durable HKO publication journal.
 *
 * This layer makes FCM a wake-up transport instead of the source of truth.
 * Every newly observed HKO publication is appended to Google Sheets before its
 * notification wake-up is queued. Android can then recover missed FCM messages
 * by reading the journal with an increasing cursor.
 */

const JOURNAL_CONFIG = Object.freeze({
  triggerFunction: 'checkWeatherUpdatesJournalled',
  spreadsheetPropertyKey: 'HKO_NOTIFICATION_JOURNAL_SPREADSHEET_ID',
  sheetName: 'events',
  stateIndexKey: 'HKO_PUBLICATION_STATE_INDEX_V7',
  stateItemPrefix: 'HKO_PUBLICATION_STATE_ITEM_V7_',
  apiPageSize: 100,
  apiMaxPageSize: 200,
  schemaVersion: 1,
  fcmSchemaVersion: '4',
  maxPreviewBodyBytes: 900,
});

const JOURNAL_HEADERS = Object.freeze([
  'cursor',
  'eventId',
  'eventJson',
  'journalledAtEpochMs',
]);

/**
 * One-time owner setup for the reliable notification path.
 *
 * Before using this in production, deploy the Apps Script project as a Web App
 * that executes as the owner and can be accessed by anyone. The endpoint only
 * exposes already-public HKO publications and contains no Firebase credentials.
 */
function setupReliableNotifications() {
  assertConfiguration_();
  const properties = PropertiesService.getScriptProperties();
  const sheet = ensureJournalSheet_(properties);

  ScriptApp.getProjectTriggers()
    .filter(function (trigger) {
      const handler = trigger.getHandlerFunction();
      return handler === 'checkWeatherUpdates' || handler === JOURNAL_CONFIG.triggerFunction;
    })
    .forEach(function (trigger) {
      ScriptApp.deleteTrigger(trigger);
    });

  ScriptApp.newTrigger(JOURNAL_CONFIG.triggerFunction)
    .timeBased()
    .everyMinutes(1)
    .create();

  const serviceUrl = journalServiceUrl_();
  console.log('Notification journal sheet: ' + sheet.getParent().getUrl());
  if (serviceUrl) {
    console.log('Notification journal API: ' + serviceUrl);
  } else {
    console.warn(
      'No Web App deployment URL is available yet. Deploy this Apps Script as a Web App before production.',
    );
  }
}

/** Compatibility setup alias for owners upgrading from the V6 monitor. */
function installReliableNotificationTrigger() {
  setupReliableNotifications();
}

/**
 * Polls HKO, appends every new source publication to the durable journal, then
 * queues an FCM wake-up that points Android at the authoritative journal cursor.
 */
function checkWeatherUpdatesJournalled() {
  const lock = LockService.getScriptLock();
  if (!lock.tryLock(20000)) {
    console.log('A previous journalled alert check is still running; this execution was skipped.');
    return;
  }

  try {
    assertConfiguration_();
    const properties = PropertiesService.getScriptProperties();
    if (typeof notificationPipelineMarkAttempt_ === 'function') {
      notificationPipelineMarkAttempt_(properties, Date.now());
    }
    const retryResult = flushJournalOutbox_(properties, Date.now());

    const responses = UrlFetchApp.fetchAll([
      hkoRequest_('warnsum'),
      hkoRequest_('warningInfo'),
      hkoRequest_('swt'),
    ]);
    const payloads = responses.map(parseHkoResponse_);
    if (typeof notificationPipelineMarkSourceSuccess_ === 'function') {
      notificationPipelineMarkSourceSuccess_(properties, Date.now());
    }
    const publications = normaliseJournalPublications_(payloads[0], payloads[1], payloads[2]);
    const currentState = journalStateForPublications_(publications);
    const previousState = readJournalState_(properties);
    const newPublications = previousState === null
      ? publications
      : publications.filter(function (publication) { return !previousState[publication.id]; });

    // The journal write comes first. If execution stops afterwards, the next
    // run finds the same eventId in the sheet and resumes without duplication.
    const journalEvents = ensureJournalEvents_(properties, newPublications, Date.now());
    if (typeof notificationPipelineMarkJournalCommit_ === 'function') {
      notificationPipelineMarkJournalCommit_(properties, Date.now(), journalEvents);
    }
    const outbox = enqueueJournalEvents_(readOutbox_(properties), journalEvents, Date.now());

    // The outbox is persisted before advancing source state. If the state write
    // fails, deterministic event IDs prevent a duplicate queue entry next run.
    writeOutbox_(properties, outbox);
    writeJournalState_(properties, currentState);

    const result = flushJournalOutbox_(properties, Date.now());
    const sent = retryResult.sent + result.sent;
    const failed = retryResult.failed + result.failed;
    if (typeof notificationPipelineMarkFlush_ === 'function') {
      notificationPipelineMarkFlush_(properties, Date.now(), result.pending, failed);
    }
    if (typeof refreshNotificationPipelineHealth_ === 'function') {
      refreshNotificationPipelineHealth_(properties, Date.now());
    }
    console.log(
      'Journalled ' + journalEvents.length + ' new HKO publication(s); sent ' + sent +
      ', pending ' + result.pending + '.',
    );
    if (failed > 0) {
      throw new Error(failed + ' queued FCM wake-up attempt(s) failed and will be retried.');
    }
  } catch (error) {
    try {
      const properties = PropertiesService.getScriptProperties();
      if (typeof notificationPipelineMarkFailure_ === 'function') {
        notificationPipelineMarkFailure_(properties, Date.now(), error);
      }
      if (typeof refreshNotificationPipelineHealth_ === 'function') {
        refreshNotificationPipelineHealth_(properties, Date.now());
      }
    } catch (healthError) {
      console.error(
        'Notification pipeline health recording failed: ' +
        String(healthError && healthError.message ? healthError.message : healthError),
      );
    }
    throw error;
  } finally {
    lock.releaseLock();
  }
}

/**
 * Public read-only journal endpoint.
 *
 * GET ?after=<cursor>&limit=<1..200>
 * GET ?mode=health
 */
function doGet(event) {
  const properties = PropertiesService.getScriptProperties();
  const parameters = event && event.parameter ? event.parameter : {};

  if (String(parameters.mode || '').toLowerCase() === 'health') {
    const health = typeof refreshNotificationPipelineHealth_ === 'function'
      ? refreshNotificationPipelineHealth_(properties, Date.now())
      : null;
    return ContentService
      .createTextOutput(JSON.stringify({
        schemaVersion: 1,
        generatedAtEpochMs: Date.now(),
        health: health,
      }))
      .setMimeType(ContentService.MimeType.JSON);
  }

  const sheet = ensureJournalSheet_(properties);
  const after = parseNonNegativeInteger_(parameters.after, 0);
  const requestedLimit = parsePositiveInteger_(parameters.limit, JOURNAL_CONFIG.apiPageSize);
  const limit = Math.min(requestedLimit, JOURNAL_CONFIG.apiMaxPageSize);
  const page = readJournalPage_(sheet, after, limit);

  return ContentService
    .createTextOutput(JSON.stringify({
      schemaVersion: JOURNAL_CONFIG.schemaVersion,
      generatedAtEpochMs: Date.now(),
      nextCursor: page.nextCursor,
      latestCursor: page.latestCursor,
      hasMore: page.hasMore,
      events: page.events,
    }))
    .setMimeType(ContentService.MimeType.JSON);
}

/** Returns setup information without exposing credentials. */
function verifyReliableNotificationSetup() {
  assertConfiguration_();
  const properties = PropertiesService.getScriptProperties();
  const sheet = ensureJournalSheet_(properties);
  const latestCursor = Math.max(0, sheet.getLastRow() - 1);
  const result = {
    journalSpreadsheetId: properties.getProperty(JOURNAL_CONFIG.spreadsheetPropertyKey),
    journalUrl: journalServiceUrl_(),
    latestCursor: latestCursor,
    pendingOutboxEvents: readOutbox_(properties).length,
    triggerCount: ScriptApp.getProjectTriggers().filter(function (trigger) {
      return trigger.getHandlerFunction() === JOURNAL_CONFIG.triggerFunction;
    }).length,
    pipelineHealth: typeof refreshNotificationPipelineHealth_ === 'function'
      ? refreshNotificationPipelineHealth_(properties, Date.now())
      : null,
  };
  console.log(JSON.stringify(result));
  return result;
}

/** Pure source normalisation used by the monitor and unit tests. */
function normaliseJournalPublications_(summary, detailPayload, tipPayload) {
  const details = Array.isArray(detailPayload && detailPayload.details) ? detailPayload.details : [];
  const publications = [];
  const matchedDetails = {};
  const pre8BodyHashes = {};

  Object.keys(summary || {}).sort().forEach(function (family) {
    const row = summary[family] || {};
    const code = cleanInlineText_(row.code || family) || family;
    const actionCode = normaliseActionCode_(row.actionCode || 'ISSUE');
    const match = findDetailForSummary_(details, family, code, actionCode);
    const detail = match ? match.detail : {};
    if (match) matchedDetails[match.index] = true;
    const body = detailText_(detail);
    const title = cleanInlineText_(row.type || row.name || warningName_(code));
    publications.push(buildJournalPublication_({
      sourceType: 'WARNING',
      sourceKey: 'warning:' + (family || code),
      family: family,
      code: code,
      actionCode: actionCode,
      title: title,
      body: body || title,
      issueTime: String(row.issueTime || detail.issueTime || ''),
      expireTime: String(row.expireTime || detail.expireTime || ''),
      updateTime: String(row.updateTime || detail.updateTime || ''),
      severity: severity_(code, false),
      isTip: false,
    }));
  });

  details.forEach(function (detail, index) {
    if (matchedDetails[index]) return;
    const body = detailText_(detail);
    if (!body) return;
    const family = cleanInlineText_(detail.warningStatementCode || 'WARNING_INFO');
    const code = cleanInlineText_(detail.subtype || family) || family;
    const title = code === 'WTCPRE8' || family === 'WTCPRE8'
      ? '預警八號熱帶氣旋警告信號特別報告'
      : detailTitle_(family, code);
    const publication = buildJournalPublication_({
      sourceType: 'STATEMENT',
      sourceKey: 'statement:' + family + ':' + code,
      family: family,
      code: code,
      actionCode: 'STATEMENT',
      title: title,
      body: body,
      issueTime: String(detail.issueTime || ''),
      expireTime: String(detail.expireTime || ''),
      updateTime: String(detail.updateTime || ''),
      severity: severity_(code, false),
      isTip: false,
    });
    publications.push(publication);
    if (code === 'WTCPRE8' || family === 'WTCPRE8') {
      pre8BodyHashes[digest_(body)] = true;
    }
  });

  const tips = Array.isArray(tipPayload && tipPayload.swt) ? tipPayload.swt : [];
  tips.forEach(function (tip) {
    const body = preserveSourceText_(tip.desc || '');
    if (!body) return;
    // HKO can surface the same pre-No.8 message through warningInfo and SWT.
    // Treat that as one user-visible publication when the official body is identical.
    if (pre8BodyHashes[digest_(body)]) return;
    const updateTime = String(tip.updateTime || '');
    publications.push(buildJournalPublication_({
      sourceType: 'SWT',
      sourceKey: 'tip:' + digest_([updateTime, body].join('|')),
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
    }));
  });

  publications.sort(function (left, right) {
    const leftTime = left.updateTime || left.issueTime || left.expireTime || '';
    const rightTime = right.updateTime || right.issueTime || right.expireTime || '';
    if (leftTime !== rightTime) return leftTime < rightTime ? -1 : 1;
    return left.id < right.id ? -1 : left.id > right.id ? 1 : 0;
  });
  return publications;
}

function buildJournalPublication_(input) {
  const body = preserveSourceText_(input.body || input.title || '');
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
    severity: input.severity || 'ADVISORY',
    isTip: Boolean(input.isTip),
  };
}

function journalStateForPublications_(publications) {
  const state = {};
  publications.forEach(function (publication) {
    state[publication.id] = {
      id: publication.id,
      sourceKey: publication.sourceKey,
      actionCode: publication.actionCode,
      sourceTime: publication.updateTime || publication.issueTime || publication.expireTime || '',
    };
  });
  return state;
}

function journalEventForPublication_(publication, detectedAtEpochMs) {
  const eventId = 'hko:' + digest_([publication.actionCode, publication.id].join('|'));
  const sourceTime = publication.updateTime || publication.issueTime || publication.expireTime || '';
  return {
    eventId: eventId,
    title: publication.title,
    body: publication.body,
    channel: channelFor_(publication.severity, publication.isTip),
    target: 'weathermetro://current/alerts' +
      '?alertId=' + encodeURIComponent(publication.sourceKey) +
      '&code=' + encodeURIComponent(publication.code) +
      '&kind=' + encodeURIComponent(publication.actionCode),
    alertId: publication.sourceKey,
    alertCode: publication.code,
    eventKind: publication.actionCode,
    sourceType: publication.sourceType,
    sourceTime: sourceTime,
    sentAtEpochMillis: detectedAtEpochMs,
    journalCursor: 0,
  };
}

function ensureJournalEvents_(properties, publications, detectedAtEpochMs) {
  if (publications.length === 0) return [];
  const sheet = ensureJournalSheet_(properties);
  return publications.map(function (publication) {
    const desired = journalEventForPublication_(publication, detectedAtEpochMs);
    const existing = findJournalEvent_(sheet, desired.eventId);
    if (existing) return existing;

    const cursor = Math.max(1, sheet.getLastRow());
    const stored = Object.assign({}, desired, { journalCursor: cursor });
    sheet.appendRow([
      cursor,
      stored.eventId,
      JSON.stringify(stored),
      detectedAtEpochMs,
    ]);
    SpreadsheetApp.flush();
    return stored;
  });
}

function findJournalEvent_(sheet, eventId) {
  const lastRow = sheet.getLastRow();
  if (lastRow <= 1) return null;
  const finder = sheet
    .getRange(2, 2, lastRow - 1, 1)
    .createTextFinder(eventId)
    .matchEntireCell(true);
  const cell = finder.findNext();
  if (!cell) return null;
  const eventJson = sheet.getRange(cell.getRow(), 3).getDisplayValue();
  const parsed = safeParse_(eventJson, null);
  if (!parsed || parsed.eventId !== eventId) {
    throw new Error('Corrupt journal event row for ' + eventId);
  }
  return parsed;
}

function readJournalPage_(sheet, after, limit) {
  const lastRow = sheet.getLastRow();
  const latestCursor = Math.max(0, lastRow - 1);
  if (latestCursor === 0 || after >= latestCursor) {
    return {
      events: [],
      nextCursor: Math.min(after, latestCursor),
      latestCursor: latestCursor,
      hasMore: false,
    };
  }

  const startCursor = after + 1;
  const startRow = startCursor + 1;
  const count = Math.min(limit, latestCursor - after);
  const rows = sheet.getRange(startRow, 1, count, JOURNAL_HEADERS.length).getDisplayValues();
  const events = rows.map(function (row) {
    const cursor = Number(row[0]);
    const parsed = safeParse_(row[2], null);
    if (!Number.isFinite(cursor) || cursor <= 0 || !parsed || parsed.eventId !== row[1]) {
      throw new Error('Corrupt notification journal row at cursor ' + row[0]);
    }
    if (Number(parsed.journalCursor) !== cursor) {
      throw new Error('Journal cursor mismatch at cursor ' + cursor);
    }
    return parsed;
  });
  const nextCursor = events.length > 0
    ? Number(events[events.length - 1].journalCursor)
    : after;
  return {
    events: events,
    nextCursor: nextCursor,
    latestCursor: latestCursor,
    hasMore: nextCursor < latestCursor,
  };
}

function ensureJournalSheet_(properties) {
  let spreadsheetId = properties.getProperty(JOURNAL_CONFIG.spreadsheetPropertyKey);
  let spreadsheet;
  if (spreadsheetId) {
    spreadsheet = SpreadsheetApp.openById(spreadsheetId);
  } else {
    spreadsheet = SpreadsheetApp.create('Weather Metro Notification Journal');
    spreadsheetId = spreadsheet.getId();
    if (!properties.setProperty(JOURNAL_CONFIG.spreadsheetPropertyKey, spreadsheetId)) {
      // Apps Script returns the property store rather than a boolean, but this
      // guard remains harmless in mocked runtimes.
    }
  }

  let sheet = spreadsheet.getSheetByName(JOURNAL_CONFIG.sheetName);
  if (!sheet) {
    const sheets = spreadsheet.getSheets();
    if (sheets.length === 1 && sheets[0].getLastRow() === 0) {
      sheet = sheets[0];
      sheet.setName(JOURNAL_CONFIG.sheetName);
    } else {
      sheet = spreadsheet.insertSheet(JOURNAL_CONFIG.sheetName);
    }
  }
  ensureJournalHeaders_(sheet);
  return sheet;
}

function ensureJournalHeaders_(sheet) {
  if (sheet.getLastRow() === 0) {
    sheet.appendRow(JOURNAL_HEADERS.slice());
    return;
  }
  const headers = sheet.getRange(1, 1, 1, JOURNAL_HEADERS.length).getDisplayValues()[0];
  if (JSON.stringify(headers) !== JSON.stringify(JOURNAL_HEADERS)) {
    throw new Error('Notification journal header mismatch; refusing to overwrite existing data.');
  }
}

function readJournalState_(properties) {
  const indexText = properties.getProperty(JOURNAL_CONFIG.stateIndexKey);
  if (indexText === null) return null;
  const ids = safeParse_(indexText, []);
  if (!Array.isArray(ids)) throw new Error('Invalid journal state index.');
  const state = {};
  ids.forEach(function (id) {
    const value = properties.getProperty(journalStatePropertyKey_(id));
    if (value === null) throw new Error('Missing journal state property for ' + id);
    const item = safeParse_(value, null);
    if (!item) throw new Error('Invalid journal state property for ' + id);
    state[id] = item;
  });
  return state;
}

function writeJournalState_(properties, state) {
  const previousIds = safeParse_(properties.getProperty(JOURNAL_CONFIG.stateIndexKey), []);
  const nextIds = Object.keys(state).sort();
  const nextIdSet = {};
  const values = {};
  nextIds.forEach(function (id) {
    nextIdSet[id] = true;
    values[journalStatePropertyKey_(id)] = JSON.stringify(state[id]);
  });
  values[JOURNAL_CONFIG.stateIndexKey] = JSON.stringify(nextIds);
  properties.setProperties(values, false);
  (Array.isArray(previousIds) ? previousIds : []).forEach(function (id) {
    if (!nextIdSet[id]) properties.deleteProperty(journalStatePropertyKey_(id));
  });
}

function journalStatePropertyKey_(id) {
  return JOURNAL_CONFIG.stateItemPrefix + digest_(id);
}

function enqueueJournalEvents_(outbox, events, nowEpochMs) {
  const queued = outbox.slice();
  const knownIds = {};
  queued.forEach(function (entry) { knownIds[entry.id] = true; });
  const journalUrl = journalServiceUrl_();

  events.forEach(function (event) {
    if (knownIds[event.eventId]) return;
    const preview = truncateUtf8_(event.body, JOURNAL_CONFIG.maxPreviewBodyBytes);
    const message = {
      title: event.title,
      body: preview,
      bodyTruncated: preview !== event.body ? 'true' : 'false',
      channel: event.channel,
      eventId: event.eventId,
      alertId: event.alertId,
      alertCode: event.alertCode,
      eventKind: event.eventKind,
      sourceType: event.sourceType,
      sourceTime: event.sourceTime,
      target: event.target,
      sentAtEpochMs: String(event.sentAtEpochMillis || nowEpochMs),
      schemaVersion: JOURNAL_CONFIG.fcmSchemaVersion,
      journalUrl: journalUrl,
      journalCursor: String(event.journalCursor),
    };
    queued.push({
      id: event.eventId,
      message: message,
      attempts: 0,
      queuedAtEpochMs: nowEpochMs,
      nextAttemptEpochMs: 0,
      lastError: '',
    });
    knownIds[event.eventId] = true;
  });

  if (queued.length > CONFIG.maxOutboxEvents) {
    throw new Error('FCM outbox capacity exceeded; refusing to discard journalled weather events.');
  }
  return queued;
}

function flushJournalOutbox_(properties, nowEpochMs) {
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
      sendJournalFcm_(entry.message);
      sent += 1;
    } catch (error) {
      const attempts = Number(entry.attempts || 0) + 1;
      entry.attempts = attempts;
      entry.lastError = String(error && error.message ? error.message : error).slice(0, 500);
      entry.nextAttemptEpochMs = nowEpochMs + retryDelayMillis_(attempts);
      remaining.push(entry);
      failed += 1;
      console.error('Journal FCM event ' + entry.id + ' failed: ' + entry.lastError);
    }
  });
  writeOutbox_(properties, remaining);
  return { sent: sent, failed: failed, pending: remaining.length };
}

function sendJournalFcm_(message) {
  const props = PropertiesService.getScriptProperties();
  const projectId = props.getProperty('FIREBASE_PROJECT_ID');
  const endpoint = 'https://fcm.googleapis.com/v1/projects/' + encodeURIComponent(projectId) + '/messages:send';
  const payload = buildJournalFcmPayload_(message);
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

function buildJournalFcmPayload_(message) {
  return buildFcmPayload_(message, {
    channel: message.channel || 'weather_alert_general',
    eventId: message.eventId || '',
    alertId: message.alertId || '',
    alertCode: message.alertCode || '',
    eventKind: message.eventKind || '',
    sourceType: message.sourceType || '',
    sourceTime: message.sourceTime || '',
    target: message.target || 'weathermetro://current',
    bodyTruncated: message.bodyTruncated || 'false',
    sentAtEpochMs: message.sentAtEpochMs || String(Date.now()),
    schemaVersion: message.schemaVersion || JOURNAL_CONFIG.fcmSchemaVersion,
    journalUrl: message.journalUrl || journalServiceUrl_(),
    journalCursor: message.journalCursor || '0',
  });
}

function journalServiceUrl_() {
  try {
    return ScriptApp.getService().getUrl() || '';
  } catch (error) {
    return '';
  }
}

function parseNonNegativeInteger_(value, fallback) {
  const number = Number(value);
  if (!Number.isFinite(number) || number < 0) return fallback;
  return Math.floor(number);
}

function parsePositiveInteger_(value, fallback) {
  const number = Number(value);
  if (!Number.isFinite(number) || number <= 0) return fallback;
  return Math.floor(number);
}

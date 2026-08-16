/**
 * Weather Metro notification source redundancy monitor.
 *
 * Phase 2C1 deliberately runs in shadow mode: it compares the official HKO
 * JSON warning summary with HKO's separately hosted RSS warning summary and
 * records compact health only. It never creates a user-visible weather alert,
 * so a parser/source mismatch cannot duplicate or suppress the production
 * journal stream while the secondary detector is being validated in production.
 */

const SOURCE_REDUNDANCY_CONFIG = Object.freeze({
  triggerFunction: 'checkWarningSourceRedundancy',
  warningSummaryRssUrl: 'https://rss.weather.gov.hk/rss/WeatherWarningSummaryv2_uc.xml',
  healthPropertyKey: 'HKO_WARNING_SOURCE_CROSSCHECK_HEALTH_V1',
  intervalMinutes: 5,
  schemaVersion: 1,
});

const WARNING_TOKEN_RULES = Object.freeze([
  { token: 'TC:1', pattern: /一號戒備信號|一號熱帶氣旋警告信號/ },
  { token: 'TC:3', pattern: /三號強風信號/ },
  { token: 'TC:8', pattern: /八號(?:東北|西北|東南|西南)?烈風或暴風信號|八號熱帶氣旋警告信號/ },
  { token: 'TC:9', pattern: /九號烈風或暴風風力增強信號|九號熱帶氣旋警告信號/ },
  { token: 'TC:10', pattern: /十號颶風信號|十號熱帶氣旋警告信號/ },
  { token: 'RAIN:AMBER', pattern: /黃色暴雨警告信號/ },
  { token: 'RAIN:RED', pattern: /紅色暴雨警告信號/ },
  { token: 'RAIN:BLACK', pattern: /黑色暴雨警告信號/ },
  { token: 'THUNDERSTORM', pattern: /雷暴警告/ },
  { token: 'HOT', pattern: /酷熱天氣警告/ },
  { token: 'COLD', pattern: /寒冷天氣警告/ },
  { token: 'FROST', pattern: /霜凍警告/ },
  { token: 'FIRE', pattern: /火災危險警告/ },
  { token: 'LANDSLIP', pattern: /山泥傾瀉警告/ },
  { token: 'NT_FLOOD', pattern: /新界北部水浸特別報告/ },
  { token: 'MONSOON', pattern: /強烈季候風信號/ },
  { token: 'TSUNAMI', pattern: /海嘯警告/ },
]);

/**
 * Installs one low-frequency shadow trigger. The production journal remains on
 * its existing one-minute trigger; this detector is intentionally separate
 * during validation to avoid changing alert semantics before observed parity is
 * proven.
 */
function setupWarningSourceRedundancy() {
  ScriptApp.getProjectTriggers()
    .filter(function (trigger) {
      return trigger.getHandlerFunction() === SOURCE_REDUNDANCY_CONFIG.triggerFunction;
    })
    .forEach(function (trigger) {
      ScriptApp.deleteTrigger(trigger);
    });

  ScriptApp.newTrigger(SOURCE_REDUNDANCY_CONFIG.triggerFunction)
    .timeBased()
    .everyMinutes(SOURCE_REDUNDANCY_CONFIG.intervalMinutes)
    .create();

  checkWarningSourceRedundancy();
}

/**
 * Compares two independent official publication paths. This function is
 * deliberately fail-soft: an RSS outage must never block the production JSON
 * journal. A persistent SECONDARY_ONLY result is the signal used by the next
 * phase to decide whether failover can be safely enabled.
 */
function checkWarningSourceRedundancy() {
  const properties = PropertiesService.getScriptProperties();
  const checkedAtEpochMs = Date.now();
  let result;

  try {
    const responses = UrlFetchApp.fetchAll([
      hkoRequest_('warnsum'),
      warningSummaryRssRequest_(),
    ]);
    result = evaluateWarningSourceCrossCheck_(responses[0], responses[1], checkedAtEpochMs);
  } catch (error) {
    result = {
      schemaVersion: SOURCE_REDUNDANCY_CONFIG.schemaVersion,
      checkedAtEpochMs: checkedAtEpochMs,
      status: 'CHECK_ERROR',
      primaryOk: false,
      secondaryOk: false,
      primaryTokens: [],
      secondaryTokens: [],
      secondaryOnly: [],
      primaryOnly: [],
      consecutiveSecondaryOnly: 0,
      secondaryOnlySignature: '',
      primaryError: '',
      secondaryError: String(error && error.message ? error.message : error).slice(0, 300),
      secondaryDigest: '',
    };
  }

  const stored = recordWarningSourceCrossCheck_(properties, result);
  if (stored.status === 'SECONDARY_ONLY' || stored.status === 'DIVERGED') {
    console.error('HKO source cross-check mismatch: ' + JSON.stringify(stored));
  } else if (stored.status !== 'MATCH') {
    console.warn('HKO source cross-check status: ' + JSON.stringify(stored));
  } else {
    console.log('HKO source cross-check matched: ' + JSON.stringify(stored));
  }
  return stored;
}

function warningSummaryRssRequest_() {
  return {
    url: SOURCE_REDUNDANCY_CONFIG.warningSummaryRssUrl,
    method: 'get',
    headers: {
      Accept: 'application/rss+xml, application/xml, text/xml, */*',
      'Cache-Control': 'no-cache',
    },
    muteHttpExceptions: true,
  };
}

function evaluateWarningSourceCrossCheck_(primaryResponse, secondaryResponse, checkedAtEpochMs) {
  const base = {
    schemaVersion: SOURCE_REDUNDANCY_CONFIG.schemaVersion,
    checkedAtEpochMs: checkedAtEpochMs,
    primaryOk: false,
    secondaryOk: false,
    primaryTokens: [],
    secondaryTokens: [],
    secondaryOnly: [],
    primaryOnly: [],
    consecutiveSecondaryOnly: 0,
    secondaryOnlySignature: '',
    primaryError: '',
    secondaryError: '',
    secondaryDigest: '',
  };

  let summary;
  try {
    summary = parseCrossCheckJsonResponse_(primaryResponse);
    base.primaryOk = true;
    base.primaryTokens = warningTokensFromSummary_(summary);
  } catch (error) {
    base.primaryError = String(error && error.message ? error.message : error).slice(0, 300);
  }

  let secondaryText = '';
  try {
    secondaryText = parseCrossCheckRssResponse_(secondaryResponse);
    base.secondaryOk = true;
    base.secondaryTokens = warningTokensFromText_(secondaryText);
    base.secondaryDigest = digest_(secondaryText);
  } catch (error) {
    base.secondaryError = String(error && error.message ? error.message : error).slice(0, 300);
  }

  if (!base.primaryOk && !base.secondaryOk) {
    base.status = 'BOTH_ERROR';
    return base;
  }
  if (!base.primaryOk) {
    base.status = 'PRIMARY_ERROR';
    return base;
  }
  if (!base.secondaryOk) {
    base.status = 'SECONDARY_ERROR';
    return base;
  }

  base.secondaryOnly = base.secondaryTokens.filter(function (token) {
    return base.primaryTokens.indexOf(token) < 0;
  });
  base.primaryOnly = base.primaryTokens.filter(function (token) {
    return base.secondaryTokens.indexOf(token) < 0;
  });
  base.secondaryOnlySignature = base.secondaryOnly.join('|');

  if (base.secondaryOnly.length > 0 && base.primaryOnly.length > 0) {
    base.status = 'DIVERGED';
  } else if (base.secondaryOnly.length > 0) {
    base.status = 'SECONDARY_ONLY';
  } else if (base.primaryOnly.length > 0) {
    base.status = 'PRIMARY_ONLY';
  } else {
    base.status = 'MATCH';
  }
  return base;
}

function parseCrossCheckJsonResponse_(response) {
  if (!response || typeof response.getResponseCode !== 'function') {
    throw new Error('Missing HKO JSON response');
  }
  const code = Number(response.getResponseCode());
  if (code < 200 || code >= 300) {
    throw new Error('HKO JSON returned HTTP ' + code);
  }
  const parsed = JSON.parse(response.getContentText('UTF-8'));
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
    throw new Error('HKO JSON warning summary is not an object');
  }
  return parsed;
}

function parseCrossCheckRssResponse_(response) {
  if (!response || typeof response.getResponseCode !== 'function') {
    throw new Error('Missing HKO RSS response');
  }
  const code = Number(response.getResponseCode());
  if (code < 200 || code >= 300) {
    throw new Error('HKO RSS returned HTTP ' + code);
  }
  const raw = String(response.getContentText('UTF-8') || '');
  if (!raw.trim()) throw new Error('HKO RSS returned an empty body');
  const visible = rssVisibleText_(raw);
  if (!visible) throw new Error('HKO RSS did not contain visible text');
  return visible;
}

/**
 * The cross-check only needs the warning names, so it deliberately avoids a
 * schema-specific RSS parser. CDATA/HTML presentation can change without
 * breaking detection as long as the official warning wording remains visible.
 */
function rssVisibleText_(xml) {
  return decodeXmlEntities_(String(xml || '')
    .replace(/<!\[CDATA\[([\s\S]*?)\]\]>/gi, '$1')
    .replace(/<br\s*\/?>/gi, '\n')
    .replace(/<\/p\s*>/gi, '\n')
    .replace(/<[^>]+>/g, ' '))
    .replace(/\r\n?/g, '\n')
    .replace(/[\t\f\v ]+/g, ' ')
    .replace(/ *\n */g, '\n')
    .replace(/\n{3,}/g, '\n\n')
    .trim();
}

function decodeXmlEntities_(value) {
  return String(value || '')
    .replace(/&nbsp;/gi, ' ')
    .replace(/&amp;/gi, '&')
    .replace(/&lt;/gi, '<')
    .replace(/&gt;/gi, '>')
    .replace(/&quot;/gi, '"')
    .replace(/&apos;/gi, "'")
    .replace(/&#(\d+);/g, function (_match, digits) {
      const codePoint = Number(digits);
      return Number.isFinite(codePoint) ? String.fromCodePoint(codePoint) : '';
    })
    .replace(/&#x([0-9a-f]+);/gi, function (_match, digits) {
      const codePoint = parseInt(digits, 16);
      return Number.isFinite(codePoint) ? String.fromCodePoint(codePoint) : '';
    });
}

function warningTokensFromSummary_(summary) {
  const parts = [];
  Object.keys(summary || {}).sort().forEach(function (family) {
    const row = summary[family] || {};
    if (normaliseActionCode_(row.actionCode || 'ISSUE') === 'CANCEL') return;
    parts.push(family);
    parts.push(String(row.code || ''));
    parts.push(String(row.type || row.name || ''));
  });
  return warningTokensFromText_(parts.join('\n'));
}

function warningTokensFromText_(value) {
  const text = String(value || '');
  const tokens = [];
  WARNING_TOKEN_RULES.forEach(function (rule) {
    if (rule.pattern.test(text) && tokens.indexOf(rule.token) < 0) {
      tokens.push(rule.token);
    }
  });
  return tokens.sort();
}

function recordWarningSourceCrossCheck_(properties, result) {
  const previous = readWarningSourceCrossCheck_(properties);
  const next = Object.assign({}, result);
  if (next.secondaryOnly && next.secondaryOnly.length > 0) {
    const sameMismatch = previous &&
      previous.secondaryOnlySignature === next.secondaryOnlySignature &&
      (previous.status === 'SECONDARY_ONLY' || previous.status === 'DIVERGED');
    next.consecutiveSecondaryOnly = sameMismatch
      ? Number(previous.consecutiveSecondaryOnly || 0) + 1
      : 1;
  } else {
    next.consecutiveSecondaryOnly = 0;
  }
  properties.setProperty(SOURCE_REDUNDANCY_CONFIG.healthPropertyKey, JSON.stringify(next));
  return next;
}

function readWarningSourceCrossCheck_(properties) {
  const raw = properties.getProperty(SOURCE_REDUNDANCY_CONFIG.healthPropertyKey);
  if (!raw) return null;
  try {
    const parsed = JSON.parse(raw);
    return parsed && typeof parsed === 'object' ? parsed : null;
  } catch (_error) {
    return null;
  }
}

/** Returns source-cross-check state without exposing any feed body or credentials. */
function verifyWarningSourceRedundancy() {
  const properties = PropertiesService.getScriptProperties();
  const result = {
    warningSummaryRssUrl: SOURCE_REDUNDANCY_CONFIG.warningSummaryRssUrl,
    triggerCount: ScriptApp.getProjectTriggers().filter(function (trigger) {
      return trigger.getHandlerFunction() === SOURCE_REDUNDANCY_CONFIG.triggerFunction;
    }).length,
    health: readWarningSourceCrossCheck_(properties),
  };
  console.log(JSON.stringify(result));
  return result;
}

import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import fs from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';

function loadScript() {
  const context = {
    console,
    Date,
    Math,
    JSON,
    Number,
    Object,
    String,
    encodeURIComponent,
    Utilities: {
      DigestAlgorithm: { SHA_256: 'SHA_256' },
      Charset: { UTF_8: 'UTF_8' },
      newBlob(value) {
        return { getBytes: () => [...Buffer.from(String(value), 'utf8')] };
      },
      computeDigest(_algorithm, value) {
        return [...crypto.createHash('sha256').update(String(value), 'utf8').digest()];
      },
      base64EncodeWebSafe(value) {
        return Buffer.from(String(value), 'utf8').toString('base64url');
      },
    },
  };
  vm.createContext(context);
  vm.runInContext(
    fs.readFileSync(new URL('./Code.gs', import.meta.url), 'utf8'),
    context,
    { filename: 'Code.gs' },
  );
  vm.runInContext(
    fs.readFileSync(new URL('./SourceRedundancy.gs', import.meta.url), 'utf8'),
    context,
    { filename: 'SourceRedundancy.gs' },
  );
  return context;
}

function response(code, body) {
  return {
    getResponseCode() { return code; },
    getContentText() { return body; },
  };
}

function rss(...warnings) {
  return `<?xml version="1.0" encoding="UTF-8"?>
    <rss version="2.0"><channel>
      <title>香港天文台天氣警告</title>
      <item><description><![CDATA[
        <p>${warnings.join('<br>')}</p>
      ]]></description></item>
    </channel></rss>`;
}

function summary(rows) {
  const value = {};
  rows.forEach(([key, name, actionCode = 'ISSUE']) => {
    value[key] = { code: key, name, actionCode };
  });
  return value;
}

function propertyStore(initial = {}) {
  const values = { ...initial };
  return {
    getProperty(key) { return Object.hasOwn(values, key) ? values[key] : null; },
    setProperty(key, value) { values[key] = String(value); return this; },
    values,
  };
}

test('RSS visible-text normalisation survives CDATA, HTML and XML entities', () => {
  const script = loadScript();
  const visible = script.rssVisibleText_(rss('酷熱天氣警告', '黃色暴雨警告信號 &amp; 雷暴警告'));
  assert.match(visible, /酷熱天氣警告/);
  assert.match(visible, /黃色暴雨警告信號 & 雷暴警告/);
  assert.deepEqual(
    [...script.warningTokensFromText_(visible)],
    ['HOT', 'RAIN:AMBER', 'THUNDERSTORM'],
  );
});

test('matching JSON and RSS active-warning sets produce MATCH', () => {
  const script = loadScript();
  const json = summary([
    ['WHOT', '酷熱天氣警告'],
    ['WTS', '雷暴警告'],
  ]);
  const result = script.evaluateWarningSourceCrossCheck_(
    response(200, JSON.stringify(json)),
    response(200, rss('酷熱天氣警告', '雷暴警告')),
    1234,
  );
  assert.equal(result.status, 'MATCH');
  assert.deepEqual([...result.primaryTokens], ['HOT', 'THUNDERSTORM']);
  assert.deepEqual([...result.secondaryTokens], ['HOT', 'THUNDERSTORM']);
  assert.deepEqual([...result.secondaryOnly], []);
  assert.deepEqual([...result.primaryOnly], []);
});

test('RSS-only active warning is surfaced as SECONDARY_ONLY without fabricating an alert', () => {
  const script = loadScript();
  const result = script.evaluateWarningSourceCrossCheck_(
    response(200, JSON.stringify(summary([['WHOT', '酷熱天氣警告']]))),
    response(200, rss('酷熱天氣警告', '紅色暴雨警告信號')),
    2000,
  );
  assert.equal(result.status, 'SECONDARY_ONLY');
  assert.deepEqual([...result.secondaryOnly], ['RAIN:RED']);
  assert.equal(result.secondaryDigest.length > 10, true);
});

test('CANCEL rows are excluded from the primary active-warning set', () => {
  const script = loadScript();
  const tokens = script.warningTokensFromSummary_(summary([
    ['WHOT', '酷熱天氣警告', 'CANCEL'],
    ['WTS', '雷暴警告', 'UPDATE'],
  ]));
  assert.deepEqual([...tokens], ['THUNDERSTORM']);
});

test('secondary HTTP failure is recorded fail-soft and does not throw', () => {
  const script = loadScript();
  const result = script.evaluateWarningSourceCrossCheck_(
    response(200, JSON.stringify(summary([['WHOT', '酷熱天氣警告']]))),
    response(503, 'unavailable'),
    3000,
  );
  assert.equal(result.status, 'SECONDARY_ERROR');
  assert.equal(result.primaryOk, true);
  assert.equal(result.secondaryOk, false);
  assert.match(result.secondaryError, /HTTP 503/);
});

test('persistent identical RSS-only mismatch increments a compact streak', () => {
  const script = loadScript();
  const properties = propertyStore();
  const first = script.recordWarningSourceCrossCheck_(properties, {
    status: 'SECONDARY_ONLY',
    secondaryOnly: ['RAIN:RED'],
    secondaryOnlySignature: 'RAIN:RED',
  });
  const second = script.recordWarningSourceCrossCheck_(properties, {
    status: 'SECONDARY_ONLY',
    secondaryOnly: ['RAIN:RED'],
    secondaryOnlySignature: 'RAIN:RED',
  });
  assert.equal(first.consecutiveSecondaryOnly, 1);
  assert.equal(second.consecutiveSecondaryOnly, 2);
  const serialized = properties.getProperty('HKO_WARNING_SOURCE_CROSSCHECK_HEALTH_V1');
  assert.equal(serialized.includes('<rss'), false);
  assert.equal(Buffer.byteLength(serialized, 'utf8') < 4096, true);
});

test('rainstorm and tropical-cyclone severity levels canonicalise to stable tokens', () => {
  const script = loadScript();
  const tokens = script.warningTokensFromText_(
    '八號東北烈風或暴風信號\n黑色暴雨警告信號\n強烈季候風信號',
  );
  assert.deepEqual([...tokens], ['MONSOON', 'RAIN:BLACK', 'TC:8']);
});

test('token comparison can resolve a propagation-lag mismatch without changing identity rules', () => {
  const script = loadScript();
  const result = script.applyWarningTokenComparison_({
    primaryTokens: ['HOT', 'RAIN:RED'],
    secondaryTokens: ['HOT', 'RAIN:RED'],
  });
  assert.equal(result.status, 'MATCH');
  assert.deepEqual([...result.secondaryOnly], []);
  assert.deepEqual([...result.primaryOnly], []);
});

test('primary retry failure is fail-soft and preserves the original RSS-only gap', () => {
  const script = loadScript();
  const initial = {
    primaryOk: true,
    secondaryOk: true,
    status: 'SECONDARY_ONLY',
    primaryTokens: ['HOT'],
    secondaryTokens: ['HOT', 'RAIN:RED'],
    secondaryOnly: ['RAIN:RED'],
    primaryOnly: [],
    secondaryOnlySignature: 'RAIN:RED',
  };
  const retried = script.retryPrimarySourceGap_(initial);
  assert.equal(retried.status, 'SECONDARY_ONLY');
  assert.deepEqual([...retried.secondaryOnly], ['RAIN:RED']);
  assert.equal(retried.primaryRetryAttempts, 2);
  assert.match(retried.primaryError, /Retry 2/);
});

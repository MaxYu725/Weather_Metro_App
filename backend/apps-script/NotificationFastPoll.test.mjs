import assert from 'node:assert/strict';
import fs from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';

function loadScript() {
  const properties = new Map();
  let fullCalls = 0;
  let fetchCalls = 0;
  let sourceRetryCalls = 0;
  const summary = {
    WHOT: {
      code: 'WHOT',
      actionCode: 'ISSUE',
      type: '酷熱天氣警告',
      issueTime: '2026-08-16T12:00:00+08:00',
      updateTime: '',
      expireTime: '',
    },
  };
  const context = {
    console,
    Date,
    Math,
    JSON,
    Number,
    Object,
    String,
    Boolean,
    Array,
    PropertiesService: {
      getScriptProperties() {
        return propertyStore;
      },
    },
    UrlFetchApp: {
      fetch() {
        fetchCalls += 1;
        return { kind: 'response' };
      },
    },
    SOURCE_REDUNDANCY_CONFIG: {
      schemaVersion: 1,
      warningSummaryRssUrl: 'https://example.test/rss',
    },
    hkoRequest_() { return { url: 'https://example.test/warnsum' }; },
    parseHkoResponse_() { return summary; },
    digest_(value) { return `d:${String(value)}`; },
    checkWeatherUpdatesJournalled() { fullCalls += 1; },
    readOutbox_() { return []; },
    flushJournalOutbox_() { return { sent: 0, pending: 0, failed: 0 }; },
    notificationPipelineMarkAttempt_() {},
    notificationPipelineMarkSourceSuccess_() {},
    notificationPipelineMarkFlush_() {},
    notificationPipelineMarkFailure_() {},
    warningTokensFromSummary_() { return ['HOT']; },
    warningTokensFromText_() { return ['HOT']; },
    parseCrossCheckRssResponse_() { return '酷熱天氣警告'; },
    applyWarningTokenComparison_(value) {
      return { ...value, status: 'MATCH', secondaryOnly: [], primaryOnly: [] };
    },
    retryPrimarySourceGap_(value) {
      sourceRetryCalls += 1;
      return value;
    },
    recordWarningSourceCrossCheck_(_properties, value) { return value; },
  };
  const propertyStore = {
    getProperty(key) {
      return properties.has(key) ? properties.get(key) : null;
    },
    setProperty(key, value) {
      properties.set(key, String(value));
    },
  };
  vm.createContext(context);
  vm.runInContext(
    fs.readFileSync(new URL('./NotificationFastPoll.gs', import.meta.url), 'utf8'),
    context,
    { filename: 'NotificationFastPoll.gs' },
  );
  return {
    context,
    properties,
    summary,
    counts() {
      return { fullCalls, fetchCalls, sourceRetryCalls };
    },
  };
}

test('bootstrap performs a full journal hydration before committing summary digest', () => {
  const script = loadScript();
  const result = script.context.runNotificationFastPoll_(
    script.context.PropertiesService.getScriptProperties(),
    Date.now(),
  );
  assert.equal(result.mode, 'FULL');
  assert.equal(result.reason, 'BOOTSTRAP');
  assert.equal(script.counts().fullCalls, 1);
  const stored = JSON.parse(script.properties.get('HKO_NOTIFICATION_FAST_POLL_V1'));
  assert.ok(stored.committedSummaryDigest);
  assert.ok(stored.lastFullRefreshEpochMs > 0);
});

test('unchanged warnsum inside auxiliary interval uses one-request fast path', () => {
  const script = loadScript();
  const digest = script.context.notificationWarnsumDigest_(script.summary);
  script.properties.set('HKO_NOTIFICATION_FAST_POLL_V1', JSON.stringify({
    schemaVersion: 1,
    committedSummaryDigest: digest,
    lastFullRefreshEpochMs: 900_000,
  }));
  const result = script.context.runNotificationFastPoll_(
    script.context.PropertiesService.getScriptProperties(),
    1_000_000,
  );
  assert.equal(result.mode, 'FAST');
  assert.equal(result.reason, 'WARNSUM_UNCHANGED');
  assert.equal(script.counts().fullCalls, 0);
  assert.equal(script.counts().fetchCalls, 1);
});

test('changed warnsum forces immediate full hydration', () => {
  const script = loadScript();
  script.properties.set('HKO_NOTIFICATION_FAST_POLL_V1', JSON.stringify({
    schemaVersion: 1,
    committedSummaryDigest: 'different',
    lastFullRefreshEpochMs: 990_000,
  }));
  const result = script.context.runNotificationFastPoll_(
    script.context.PropertiesService.getScriptProperties(),
    1_000_000,
  );
  assert.equal(result.mode, 'FULL');
  assert.equal(result.reason, 'WARNSUM_CHANGED');
  assert.equal(script.counts().fullCalls, 1);
});

test('standalone warningInfo and SWT remain bounded by periodic full hydration', () => {
  const script = loadScript();
  const digest = script.context.notificationWarnsumDigest_(script.summary);
  script.properties.set('HKO_NOTIFICATION_FAST_POLL_V1', JSON.stringify({
    schemaVersion: 1,
    committedSummaryDigest: digest,
    lastFullRefreshEpochMs: 800_000,
  }));
  const result = script.context.runNotificationFastPoll_(
    script.context.PropertiesService.getScriptProperties(),
    1_000_000,
  );
  assert.equal(result.mode, 'FULL');
  assert.equal(result.reason, 'AUX_REFRESH_DUE');
  assert.equal(script.counts().fullCalls, 1);
});

test('RSS cross-check reuses current warnsum and preserves primary retry gate', () => {
  const script = loadScript();
  const result = script.context.checkWarningSourceRedundancyFromSummary_(script.summary, 1_000_000);
  assert.equal(result.status, 'MATCH');
  assert.deepEqual([...result.primaryTokens], ['HOT']);
  assert.equal(script.counts().fetchCalls, 1);
  assert.equal(script.counts().sourceRetryCalls, 1);
});

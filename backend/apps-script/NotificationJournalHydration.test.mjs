import assert from 'node:assert/strict';
import fs from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';

function loadScript({ newPublication = false, stateChanged = false } = {}) {
  const order = [];
  const properties = {};
  const currentPublication = { id: 'publication:new' };
  const previousState = stateChanged
    ? { old: { id: 'old' } }
    : { same: { id: 'same', sourceKey: 'warning:WHOT', actionCode: 'ISSUE', sourceTime: 't' } };
  const currentState = stateChanged
    ? { next: { id: 'next' } }
    : { same: { id: 'same', sourceKey: 'warning:WHOT', actionCode: 'ISSUE', sourceTime: 't' } };
  let writeOutboxCalls = 0;
  let writeStateCalls = 0;
  let refreshHealthCalls = 0;
  let fetchAllRequests = [];
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
    LockService: {
      getScriptLock() {
        return {
          tryLock() { return true; },
          releaseLock() { order.push('unlock'); },
        };
      },
    },
    PropertiesService: {
      getScriptProperties() { return properties; },
    },
    UrlFetchApp: {
      fetchAll(requests) {
        fetchAllRequests = requests;
        return [{ kind: 'detail' }, { kind: 'tip' }];
      },
    },
    assertConfiguration_() {},
    hkoRequest_(type) { return { url: `https://example.test/${type}`, type }; },
    parseHkoResponse_(response) {
      if (response.kind === 'detail') return { details: [] };
      if (response.kind === 'tip') return { swt: [] };
      throw new Error('unexpected response');
    },
    flushJournalOutbox_() {
      order.push('flush');
      return { sent: 0, failed: 0, pending: 0 };
    },
    normaliseJournalPublications_() {
      return newPublication ? [currentPublication] : [];
    },
    journalStateForPublications_() { return currentState; },
    readJournalState_() { return previousState; },
    ensureJournalEvents_() {
      order.push('journal');
      return [{ eventId: 'hko:new' }];
    },
    enqueueJournalEvents_(_outbox, events) {
      order.push('enqueue');
      return events.map((event) => ({ id: event.eventId }));
    },
    readOutbox_() { return []; },
    writeOutbox_() {
      writeOutboxCalls += 1;
      order.push('outbox');
    },
    writeJournalState_() {
      writeStateCalls += 1;
      order.push('state');
    },
    notificationPipelineMarkAttempt_() {},
    notificationPipelineMarkSourceSuccess_() {},
    notificationPipelineMarkJournalCommit_() {},
    notificationPipelineMarkFlush_() {},
    notificationPipelineMarkFailure_() {},
    refreshNotificationPipelineHealth_() { refreshHealthCalls += 1; },
  };
  vm.createContext(context);
  vm.runInContext(
    fs.readFileSync(new URL('./NotificationJournalHydration.gs', import.meta.url), 'utf8'),
    context,
    { filename: 'NotificationJournalHydration.gs' },
  );
  return {
    context,
    order,
    counts() {
      return { writeOutboxCalls, writeStateCalls, refreshHealthCalls, fetchAllRequests };
    },
  };
}

test('prefetched hydration fetches only warningInfo and SWT', () => {
  const script = loadScript();
  const result = script.context.checkWeatherUpdatesJournalledFromSummary_({ WHOT: {} });
  assert.equal(result.skipped, false);
  assert.deepEqual(
    script.counts().fetchAllRequests.map((request) => request.type),
    ['warningInfo', 'swt'],
  );
});

test('unchanged steady state skips outbox and state rewrites', () => {
  const script = loadScript();
  const result = script.context.checkWeatherUpdatesJournalledFromSummary_({ WHOT: {} });
  assert.equal(result.journalled, 0);
  assert.equal(result.stateChanged, false);
  assert.equal(script.counts().writeOutboxCalls, 0);
  assert.equal(script.counts().writeStateCalls, 0);
  assert.equal(script.counts().refreshHealthCalls, 0);
});

test('new publication persists durable outbox before source state', () => {
  const script = loadScript({ newPublication: true, stateChanged: true });
  const result = script.context.checkWeatherUpdatesJournalledFromSummary_({ WHOT: {} });
  assert.equal(result.journalled, 1);
  const outboxIndex = script.order.indexOf('outbox');
  const stateIndex = script.order.indexOf('state');
  assert.ok(outboxIndex >= 0);
  assert.ok(stateIndex > outboxIndex);
});

test('state disappearance is persisted without touching outbox', () => {
  const script = loadScript({ stateChanged: true });
  const result = script.context.checkWeatherUpdatesJournalledFromSummary_({});
  assert.equal(result.journalled, 0);
  assert.equal(result.stateChanged, true);
  assert.equal(script.counts().writeOutboxCalls, 0);
  assert.equal(script.counts().writeStateCalls, 1);
});

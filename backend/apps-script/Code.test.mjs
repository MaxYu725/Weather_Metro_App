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
  const source = fs.readFileSync(new URL('./Code.gs', import.meta.url), 'utf8');
  vm.runInContext(source, context, { filename: 'Code.gs' });
  return context;
}

function publication(overrides = {}) {
  return {
    id: 'publication:1',
    sourceType: 'WARNING',
    sourceKey: 'warning:WRAIN',
    family: 'WRAIN',
    code: 'WRAINB',
    actionCode: 'ISSUE',
    title: '黑色暴雨警告',
    body: '香港天文台警告內容',
    issueTime: '2026-08-15T19:00:00+08:00',
    expireTime: '',
    updateTime: '2026-08-15T19:00:00+08:00',
    updatedAt: '2026-08-15T19:00:00+08:00',
    severity: 'URGENT',
    isTip: false,
    fingerprint: 'fingerprint-1',
    ...overrides,
  };
}

function properties(initial = []) {
  const values = new Map();
  values.set('HKO_ALERT_OUTBOX_INDEX_V2', JSON.stringify(initial.map((entry) => entry.id)));
  initial.forEach((entry) => {
    values.set(`HKO_ALERT_OUTBOX_EVENT_V2_${entry.id}`, JSON.stringify(entry));
  });
  return {
    getProperty(key) { return values.has(key) ? values.get(key) : null; },
    setProperty(key, value) { values.set(key, value); },
    setProperties(entries) {
      Object.entries(entries).forEach(([key, value]) => values.set(key, value));
    },
    deleteProperty(key) { values.delete(key); },
    value() {
      const ids = JSON.parse(values.get('HKO_ALERT_OUTBOX_INDEX_V2'));
      return ids.map((id) => JSON.parse(values.get(`HKO_ALERT_OUTBOX_EVENT_V2_${id}`)));
    },
    raw() { return values; },
  };
}

function warningSummary(actionCode = 'ISSUE', updateTime = '2026-08-16T10:00:00+08:00') {
  return {
    WHOT: {
      name: '酷熱天氣警告',
      code: 'WHOT',
      actionCode,
      issueTime: '2026-08-16T09:00:00+08:00',
      updateTime,
    },
  };
}

function warningDetails(contents = ['第一段\n第二行']) {
  return {
    details: [{
      warningStatementCode: 'WHOT',
      contents,
      updateTime: '2026-08-16T10:00:00+08:00',
    }],
  };
}

test('UTF-8 truncation respects the FCM byte budget without splitting CJK text', () => {
  const script = loadScript();
  const result = script.truncateUtf8_('香港天文台'.repeat(400), 900);
  assert.ok(Buffer.byteLength(result, 'utf8') <= 900);
  assert.ok(result.endsWith('…'));
  assert.equal(result.includes('\uFFFD'), false);
});

test('first run emits the official HKO action code instead of synthesizing ISSUE', () => {
  const script = loadScript();
  const state = script.normaliseState_(warningSummary('REISSUE'), warningDetails(), { swt: [] });
  const events = script.initialEvents_(state);
  assert.equal(events.length, 1);
  assert.equal(events[0].kind, 'REISSUE');
  assert.equal(events[0].item.actionCode, 'REISSUE');
});

test('same warning text with a new official action/time is a new publication', () => {
  const script = loadScript();
  const previous = script.normaliseState_(
    warningSummary('ISSUE', '2026-08-16T10:00:00+08:00'),
    warningDetails(),
    { swt: [] },
  );
  const current = script.normaliseState_(
    warningSummary('EXTEND', '2026-08-16T11:00:00+08:00'),
    warningDetails(),
    { swt: [] },
  );
  const events = script.diffStates_(previous, current);
  assert.equal(events.length, 1);
  assert.equal(events[0].kind, 'EXTEND');
});

test('explicit HKO cancellation is preserved with its official cancellation detail', () => {
  const script = loadScript();
  const current = script.normaliseState_({
    WTCSGNL: {
      type: '熱帶氣旋警告信號',
      code: 'CANCEL',
      actionCode: 'CANCEL',
      updateTime: '2026-08-16T12:00:00+08:00',
    },
  }, {
    details: [{
      warningStatementCode: 'WTCSGNL',
      subtype: 'CANCEL',
      contents: ['所有熱帶氣旋警告信號取消。'],
      updateTime: '2026-08-16T12:00:00+08:00',
    }],
  }, { swt: [] });
  const events = script.initialEvents_(current);
  assert.equal(events.length, 1);
  assert.equal(events[0].kind, 'CANCEL');
  assert.equal(events[0].item.body, '所有熱帶氣旋警告信號取消。');
});

test('disappearance from a snapshot never fabricates a cancellation', () => {
  const script = loadScript();
  const previous = { old: publication() };
  assert.deepEqual(Array.from(script.diffStates_(previous, {})), []);
});

test('warningInfo-only WTCPRE8 is emitted as an official statement', () => {
  const script = loadScript();
  const state = script.normaliseState_({}, {
    details: [{
      warningStatementCode: 'WTCPRE8',
      contents: ['天文台將考慮在下午四時至六時之間改發八號烈風或暴風信號。'],
      updateTime: '2026-08-16T14:00:00+08:00',
    }],
  }, { swt: [] });
  const events = script.initialEvents_(state);
  assert.equal(events.length, 1);
  assert.equal(events[0].kind, 'STATEMENT');
  assert.equal(events[0].item.code, 'WTCPRE8');
  assert.equal(events[0].item.title, '八號信號預警特別報告');
});

test('Special Weather Tip disappearance never produces a fake cancel event', () => {
  const script = loadScript();
  const previous = script.normaliseState_({}, { details: [] }, {
    swt: [{ desc: '局部地區大雨提示', updateTime: '2026-08-16T10:00:00+08:00' }],
  });
  const events = script.diffStates_(previous, {});
  assert.deepEqual(Array.from(events), []);
});

test('official detail line breaks are preserved', () => {
  const script = loadScript();
  const state = script.normaliseState_(warningSummary(), warningDetails(['第一段\n第二行', '第三段']), { swt: [] });
  const item = Object.values(state)[0];
  assert.equal(item.body, '第一段\n第二行\n\n第三段');
});

test('FCM preview uses the HKO title directly and carries source semantics', () => {
  const script = loadScript();
  const event = { kind: 'REISSUE', item: publication({ actionCode: 'REISSUE' }) };
  const message = script.messageForEvent_(event, 1234);
  assert.equal(message.title, '黑色暴雨警告');
  assert.equal(message.title.startsWith('已'), false);
  assert.equal(message.eventKind, 'REISSUE');
  assert.equal(message.sourceType, 'WARNING');
  assert.equal(message.schemaVersion, '3');
});

test('queued messages remain byte-bounded and deduplicated by source publication id', () => {
  const script = loadScript();
  const event = { kind: 'ISSUE', item: publication({ body: '暴雨'.repeat(1000) }) };
  const once = script.enqueueEvents_([], [event], 1234);
  const twice = script.enqueueEvents_(once, [event], 5678);
  assert.equal(twice.length, 1);
  assert.equal(twice[0].message.schemaVersion, '3');
  assert.equal(twice[0].message.sentAtEpochMs, '1234');
  assert.equal(twice[0].message.bodyTruncated, 'true');
  assert.ok(Buffer.byteLength(twice[0].message.body, 'utf8') <= 900);
});

test('a later source publication with identical wording receives a new event id', () => {
  const script = loadScript();
  const firstState = script.normaliseState_(warningSummary('ISSUE', '2026-08-16T10:00:00+08:00'), warningDetails(), { swt: [] });
  const laterState = script.normaliseState_(warningSummary('ISSUE', '2026-08-16T11:00:00+08:00'), warningDetails(), { swt: [] });
  const first = script.messageForEvent_(script.initialEvents_(firstState)[0], 1);
  const later = script.messageForEvent_(script.initialEvents_(laterState)[0], 2);
  assert.notEqual(first.eventId, later.eventId);
});

test('failed sends stay in the durable outbox with retry metadata', () => {
  const script = loadScript();
  const queued = script.enqueueEvents_([], [{ kind: 'ISSUE', item: publication() }], 1000);
  const store = properties(queued);
  script.sendFcm_ = () => { throw new Error('temporary outage'); };
  const result = script.flushOutbox_(store, 2000);
  assert.deepEqual(JSON.parse(JSON.stringify(result)), { sent: 0, failed: 1, pending: 1 });
  assert.equal(store.value()[0].attempts, 1);
  assert.match(store.value()[0].lastError, /temporary outage/);
  assert.ok(store.value()[0].nextAttemptEpochMs > 2000);
});

test('successful sends are removed from the durable outbox', () => {
  const script = loadScript();
  const queued = script.enqueueEvents_([], [{ kind: 'ISSUE', item: publication() }], 1000);
  const store = properties(queued);
  script.sendFcm_ = () => {};
  const result = script.flushOutbox_(store, 2000);
  assert.deepEqual(JSON.parse(JSON.stringify(result)), { sent: 1, failed: 0, pending: 0 });
  assert.deepEqual(store.value(), []);
});

test('outbox stores each event below the Apps Script per-property quota', () => {
  const script = loadScript();
  const queued = [];
  for (let index = 0; index < 100; index += 1) {
    const item = publication({
      id: `publication:${index}`,
      sourceKey: `warning:${index}`,
      code: String(index),
      body: '警告'.repeat(1000),
    });
    queued.push(...script.enqueueEvents_(queued, [{ kind: 'ISSUE', item }], index + 1).slice(queued.length));
  }
  const store = properties();
  script.writeOutbox_(store, queued);
  const byteSizes = [...store.raw().values()].map((value) => Buffer.byteLength(value, 'utf8'));
  assert.equal(store.value().length, 100);
  assert.ok(Math.max(...byteSizes) < 9 * 1024);
});

test('publication state round-trips below the per-property quota', () => {
  const script = loadScript();
  const state = {};
  for (let index = 0; index < 30; index += 1) {
    state[`publication:${index}`] = publication({
      id: `publication:${index}`,
      sourceKey: `warning:${index}`,
      code: String(index),
      body: script.truncateUtf8_('天文台警告內容'.repeat(1000), 6000),
    });
  }
  const store = properties();
  script.writeState_(store, state);
  assert.deepEqual(
    JSON.parse(JSON.stringify(script.readState_(store))),
    JSON.parse(JSON.stringify(state)),
  );
  const byteSizes = [...store.raw().values()].map((value) => Buffer.byteLength(value, 'utf8'));
  assert.ok(Math.max(...byteSizes) < 9 * 1024);
});

test('corrupt indexes fail visibly instead of silently dropping a publication', () => {
  const script = loadScript();
  const queued = script.enqueueEvents_([], [{ kind: 'ISSUE', item: publication() }], 1000);
  const outboxStore = properties(queued);
  outboxStore.raw().delete(`HKO_ALERT_OUTBOX_EVENT_V2_${queued[0].id}`);
  assert.throws(() => script.readOutbox_(outboxStore), /Missing FCM outbox property/);

  const stateStore = properties();
  script.writeState_(stateStore, { 'publication:1': publication() });
  stateStore.raw().delete(`HKO_PUBLICATION_STATE_ITEM_V6_${script.digest_('publication:1')}`);
  assert.throws(() => script.readState_(stateStore), /Missing publication state property/);
});

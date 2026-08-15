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

function alert(overrides = {}) {
  return {
    id: 'warning:WRAINB',
    code: 'WRAINB',
    title: '黑色暴雨警告',
    body: '香港天文台警告內容',
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

test('UTF-8 truncation respects the FCM byte budget without splitting CJK text', () => {
  const script = loadScript();
  const result = script.truncateUtf8_('香港天文台'.repeat(400), 900);
  assert.ok(Buffer.byteLength(result, 'utf8') <= 900);
  assert.ok(result.endsWith('…'));
  assert.equal(result.includes('\uFFFD'), false);
});

test('a first run emits every active alert instead of creating a silent baseline', () => {
  const script = loadScript();
  const events = script.initialEvents_({ b: alert({ id: 'b' }), a: alert({ id: 'a' }) });
  assert.deepEqual(
    Array.from(events, (event) => `${event.kind}:${event.item.id}`),
    ['ISSUE:a', 'ISSUE:b'],
  );
});

test('diff emits issue, content update, and cancellation events', () => {
  const script = loadScript();
  const previous = {
    old: alert({ id: 'old', fingerprint: 'same' }),
    changed: alert({ id: 'changed', fingerprint: 'before' }),
  };
  const current = {
    new: alert({ id: 'new', fingerprint: 'new' }),
    changed: alert({ id: 'changed', fingerprint: 'after' }),
  };
  const events = script.diffStates_(previous, current);
  assert.deepEqual(Array.from(events, (event) => `${event.kind}:${event.item.id}`), [
    'UPDATE:changed',
    'ISSUE:new',
    'CANCEL:old',
  ]);
});

test('queued messages are byte-bounded, versioned, and deduplicated by event id', () => {
  const script = loadScript();
  const event = { kind: 'ISSUE', item: alert({ body: '暴雨'.repeat(1000) }) };
  const once = script.enqueueEvents_([], [event], 1234);
  const twice = script.enqueueEvents_(once, [event], 5678);
  assert.equal(twice.length, 1);
  assert.equal(twice[0].message.schemaVersion, '2');
  assert.equal(twice[0].message.sentAtEpochMs, '1234');
  assert.equal(twice[0].message.bodyTruncated, 'true');
  assert.ok(Buffer.byteLength(twice[0].message.body, 'utf8') <= 900);
});

test('a later occurrence with the same wording receives a new event id', () => {
  const script = loadScript();
  const first = script.messageForEvent_({ kind: 'ISSUE', item: alert() }, 1);
  const later = script.messageForEvent_({
    kind: 'ISSUE',
    item: alert({ updatedAt: '2026-08-16T19:00:00+08:00' }),
  }, 2);
  assert.notEqual(first.eventId, later.eventId);
});

test('failed sends stay in the durable outbox with retry metadata', () => {
  const script = loadScript();
  const queued = script.enqueueEvents_([], [{ kind: 'ISSUE', item: alert() }], 1000);
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
  const queued = script.enqueueEvents_([], [{ kind: 'ISSUE', item: alert() }], 1000);
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
    queued.push(...script.enqueueEvents_(queued, [{
      kind: 'ISSUE',
      item: alert({
        id: `warning:${index}`,
        code: String(index),
        fingerprint: `fingerprint-${index}`,
        body: '警告'.repeat(1000),
      }),
    }], index + 1).slice(queued.length));
  }
  const store = properties();
  script.writeOutbox_(store, queued);
  const byteSizes = [...store.raw().values()].map((value) => Buffer.byteLength(value, 'utf8'));
  assert.equal(store.value().length, 100);
  assert.ok(Math.max(...byteSizes) < 9 * 1024);
});

test('state uses per-alert properties and round-trips below the property quota', () => {
  const script = loadScript();
  const state = {};
  for (let index = 0; index < 30; index += 1) {
    state[`warning:${index}`] = alert({
      id: `warning:${index}`,
      code: String(index),
      body: script.truncateUtf8_('天文台警告內容'.repeat(1000), 3000),
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

test('corrupt indexes fail visibly instead of silently dropping an alert event', () => {
  const script = loadScript();
  const queued = script.enqueueEvents_([], [{ kind: 'ISSUE', item: alert() }], 1000);
  const outboxStore = properties(queued);
  outboxStore.raw().delete(`HKO_ALERT_OUTBOX_EVENT_V2_${queued[0].id}`);
  assert.throws(() => script.readOutbox_(outboxStore), /Missing FCM outbox property/);

  const stateStore = properties();
  script.writeState_(stateStore, { alert: alert({ id: 'alert' }) });
  stateStore.raw().delete(`HKO_ALERT_STATE_ITEM_V5_${script.digest_('alert')}`);
  assert.throws(() => script.readState_(stateStore), /Missing alert state property/);
});

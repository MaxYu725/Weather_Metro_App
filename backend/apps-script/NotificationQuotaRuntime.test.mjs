import assert from 'node:assert/strict';
import fs from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';

function loadFile(name, extra = {}) {
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
    ...extra,
  };
  vm.createContext(context);
  vm.runInContext(
    fs.readFileSync(new URL(`./${name}`, import.meta.url), 'utf8'),
    context,
    { filename: name },
  );
  return context;
}

test('healthy one-minute fast polls persist success telemetry at most every other run', () => {
  const context = loadFile('NotificationFastPoll.gs');
  const previous = 1_000_000;

  assert.equal(
    context.notificationFastPollShouldPersistPrimarySuccess_(previous, previous + 60_000),
    false,
  );
  assert.equal(
    context.notificationFastPollShouldPersistPrimarySuccess_(previous, previous + 89_999),
    false,
  );
  assert.equal(
    context.notificationFastPollShouldPersistPrimarySuccess_(previous, previous + 90_000),
    true,
  );
  assert.equal(
    context.notificationFastPollShouldPersistPrimarySuccess_(previous, previous - 1),
    true,
  );
});

test('healthy full hydration coalesces pipeline metadata into one runtime write', () => {
  let writes = 0;
  let stored = {
    lastAttemptEpochMs: 1,
    lastHkoPollSuccessEpochMs: 1,
    lastJournalCheckEpochMs: 1,
    lastJournalAppendEpochMs: 1,
    lastOutboxFlushEpochMs: 1,
    lastCompletedEpochMs: 1,
    latestJournalCursor: 13,
    pendingOutboxEvents: 1,
    lastFlushFailedEvents: 1,
    lastError: 'old',
  };
  const context = loadFile('NotificationJournalHydration.gs', {
    readNotificationPipelineRuntime_() {
      return { ...stored };
    },
    writeNotificationPipelineRuntime_(_properties, value) {
      writes += 1;
      stored = { ...value };
    },
  });

  context.notificationJournalHydrationMarkSuccess_(
    {},
    2_000_000,
    2_000_500,
    [{ journalCursor: 14 }],
    0,
    0,
  );

  assert.equal(writes, 1);
  assert.equal(stored.lastAttemptEpochMs, 2_000_000);
  assert.equal(stored.lastHkoPollSuccessEpochMs, 2_000_500);
  assert.equal(stored.lastJournalCheckEpochMs, 2_000_500);
  assert.equal(stored.lastJournalAppendEpochMs, 2_000_500);
  assert.equal(stored.latestJournalCursor, 14);
  assert.equal(stored.lastOutboxFlushEpochMs, 2_000_500);
  assert.equal(stored.pendingOutboxEvents, 0);
  assert.equal(stored.lastFlushFailedEvents, 0);
  assert.equal(stored.lastCompletedEpochMs, 2_000_500);
  assert.equal(stored.lastError, '');
});

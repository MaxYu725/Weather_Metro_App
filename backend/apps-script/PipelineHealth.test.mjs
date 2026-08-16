import assert from 'node:assert/strict';
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
  };
  vm.createContext(context);
  vm.runInContext(
    fs.readFileSync(new URL('./PipelineHealth.gs', import.meta.url), 'utf8'),
    context,
    { filename: 'PipelineHealth.gs' },
  );
  return context;
}

function facts(overrides = {}) {
  const now = 1_000_000;
  return {
    nowEpochMs: now,
    runtime: {
      lastAttemptEpochMs: now - 10_000,
      lastHkoPollSuccessEpochMs: now - 30_000,
      lastJournalCheckEpochMs: now - 30_000,
      lastJournalAppendEpochMs: now - 60_000,
      lastOutboxFlushEpochMs: now - 20_000,
      lastCompletedEpochMs: now - 20_000,
      lastFailureEpochMs: 0,
      latestJournalCursor: 8,
      lastError: '',
    },
    pendingOutboxEvents: 0,
    oldestOutboxQueuedAtEpochMs: 0,
    journalTriggerCount: 1,
    sourceTriggerCount: 1,
    sourceHealth: {
      status: 'MATCH',
      checkedAtEpochMs: now - 20_000,
      consecutiveSecondaryOnly: 0,
      secondaryOnly: [],
      primaryOnly: [],
    },
    ...overrides,
  };
}

test('fresh journal, source check and empty outbox are HEALTHY', () => {
  const script = loadScript();
  const health = script.deriveNotificationPipelineHealth_(facts());
  assert.equal(health.status, 'HEALTHY');
  assert.equal(health.healthy, true);
  assert.equal(health.actionRequired, false);
  assert.equal(health.latestJournalCursor, 8);
});

test('stale HKO polling is action-required even if source cross-check is fresh', () => {
  const script = loadScript();
  const value = facts();
  value.runtime.lastHkoPollSuccessEpochMs = value.nowEpochMs - (4 * 60 * 1000);
  const health = script.deriveNotificationPipelineHealth_(value);
  assert.equal(health.status, 'POLL_STALE');
  assert.equal(health.actionRequired, true);
});

test('oldest durable outbox event detects a stalled delivery path', () => {
  const script = loadScript();
  const value = facts({ pendingOutboxEvents: 2 });
  value.oldestOutboxQueuedAtEpochMs = value.nowEpochMs - (6 * 60 * 1000);
  const health = script.deriveNotificationPipelineHealth_(value);
  assert.equal(health.status, 'OUTBOX_STALLED');
  assert.equal(health.pendingOutboxEvents, 2);
  assert.equal(health.actionRequired, true);
});

test('two consecutive RSS-only observations become a confirmed source gap', () => {
  const script = loadScript();
  const value = facts();
  value.sourceHealth = {
    status: 'SECONDARY_ONLY',
    checkedAtEpochMs: value.nowEpochMs - 10_000,
    consecutiveSecondaryOnly: 2,
    secondaryOnly: ['RAIN:RED'],
    primaryOnly: [],
  };
  const health = script.deriveNotificationPipelineHealth_(value);
  assert.equal(health.status, 'SOURCE_GAP_CONFIRMED');
  assert.equal(health.actionRequired, true);
  assert.deepEqual([...health.sourceSecondaryOnly], ['RAIN:RED']);
});

test('one transient source mismatch is degraded but not yet action-required', () => {
  const script = loadScript();
  const value = facts();
  value.sourceHealth = {
    status: 'SECONDARY_ONLY',
    checkedAtEpochMs: value.nowEpochMs - 10_000,
    consecutiveSecondaryOnly: 1,
    secondaryOnly: ['RAIN:RED'],
    primaryOnly: [],
  };
  const health = script.deriveNotificationPipelineHealth_(value);
  assert.equal(health.status, 'SOURCE_DEGRADED');
  assert.equal(health.actionRequired, false);
});

test('resolved retry result is accepted as healthy source parity', () => {
  const script = loadScript();
  const value = facts();
  value.sourceHealth = {
    status: 'MATCH_AFTER_RETRY',
    checkedAtEpochMs: value.nowEpochMs - 10_000,
    consecutiveSecondaryOnly: 0,
    secondaryOnly: [],
    primaryOnly: [],
  };
  const health = script.deriveNotificationPipelineHealth_(value);
  assert.equal(health.status, 'HEALTHY');
});

test('missing journal trigger has highest operational priority', () => {
  const script = loadScript();
  const health = script.deriveNotificationPipelineHealth_(facts({ journalTriggerCount: 0 }));
  assert.equal(health.status, 'JOURNAL_TRIGGER_INVALID');
  assert.equal(health.actionRequired, true);
});

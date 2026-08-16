import assert from 'node:assert/strict';
import fs from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';

function loadScript(extra = {}) {
  const properties = new Map();
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
        return {
          getProperty(key) {
            return properties.has(key) ? properties.get(key) : null;
          },
          setProperty(key, value) {
            properties.set(key, String(value));
          },
        };
      },
    },
    ScriptApp: {
      getProjectTriggers() {
        return [];
      },
      deleteTrigger() {},
    },
    LockService: {
      getUserLock() {
        return {
          tryLock() { return true; },
          releaseLock() {},
        };
      },
    },
    checkWeatherUpdatesJournalled() {},
    checkWarningSourceRedundancy() {
      return {
        status: 'MATCH',
        checkedAtEpochMs: Date.now(),
        consecutiveSecondaryOnly: 0,
        secondaryOnly: [],
      };
    },
    checkSourceGapRecoveryEvidence() { return { status: 'IDLE' }; },
    checkSourceGapRecoveryFailover() { return { status: 'IDLE' }; },
    recoveryFailoverEnabled_() { return true; },
    readWarningSourceCrossCheck_() { return null; },
    refreshNotificationPipelineHealth_() { return { status: 'HEALTHY' }; },
    ...extra,
  };
  vm.createContext(context);
  vm.runInContext(
    fs.readFileSync(new URL('./NotificationSupervisor.gs', import.meta.url), 'utf8'),
    context,
    { filename: 'NotificationSupervisor.gs' },
  );
  return { context, properties };
}

test('source cross-check is due only after the steady-state interval', () => {
  const { context } = loadScript();
  const now = 1_000_000;
  assert.equal(context.shouldRunSourceCrossCheck_(null, now), true);
  assert.equal(
    context.shouldRunSourceCrossCheck_({ checkedAtEpochMs: now - 60_000 }, now),
    false,
  );
  assert.equal(
    context.shouldRunSourceCrossCheck_({ checkedAtEpochMs: now - 120_000 }, now),
    true,
  );
});

test('recovery runs only for a persistent RSS-only source gap', () => {
  const { context } = loadScript();
  assert.equal(context.shouldRunSourceGapRecovery_({ status: 'MATCH' }), false);
  assert.equal(context.shouldRunSourceGapRecovery_({
    status: 'SECONDARY_ONLY',
    consecutiveSecondaryOnly: 1,
    secondaryOnly: ['HOT'],
  }), false);
  assert.equal(context.shouldRunSourceGapRecovery_({
    status: 'SECONDARY_ONLY',
    consecutiveSecondaryOnly: 2,
    secondaryOnly: ['HOT'],
  }), true);
});

test('consumer quota projection uses measured supervisor average runtime', () => {
  const { context } = loadScript();
  const telemetry = context.deriveNotificationSupervisorQuotaTelemetry_({
    dayKey: '2026-08-16',
    dayRunCount: 20,
    dayRuntimeMs: 80_000,
    sourceChecksToday: 10,
    recoveryChecksToday: 0,
    busySkipsToday: 0,
    componentFailuresToday: 0,
    legacyTriggersRemovedToday: 0,
    lastDurationMs: 3_000,
    maxDurationMs: 5_000,
    lastStatus: 'OK',
  });
  assert.equal(telemetry.averageRunMs, 4_000);
  assert.equal(telemetry.projectedDailyRuntimeMs, 5_760_000);
  assert.equal(telemetry.projectedRuntimeRisk, true);
  assert.equal(telemetry.consumerQuotaRisk, true);
});

test('steady-state supervisor journals every run and skips recovery on MATCH', () => {
  let journalCalls = 0;
  let sourceCalls = 0;
  let evidenceCalls = 0;
  let failoverCalls = 0;
  const { context } = loadScript({
    checkWeatherUpdatesJournalled() { journalCalls += 1; },
    checkWarningSourceRedundancy() {
      sourceCalls += 1;
      return {
        status: 'MATCH',
        checkedAtEpochMs: Date.now(),
        consecutiveSecondaryOnly: 0,
        secondaryOnly: [],
      };
    },
    checkSourceGapRecoveryEvidence() { evidenceCalls += 1; return { status: 'IDLE' }; },
    checkSourceGapRecoveryFailover() { failoverCalls += 1; return { status: 'IDLE' }; },
  });

  const result = context.runNotificationSupervisor();
  assert.equal(result.status, 'OK');
  assert.equal(journalCalls, 1);
  assert.equal(sourceCalls, 1);
  assert.equal(evidenceCalls, 0);
  assert.equal(failoverCalls, 0);
});

test('persistent source gap immediately chains evidence then guarded failover', () => {
  let evidenceCalls = 0;
  let failoverCalls = 0;
  const { context } = loadScript({
    checkWarningSourceRedundancy() {
      return {
        status: 'SECONDARY_ONLY',
        checkedAtEpochMs: Date.now(),
        consecutiveSecondaryOnly: 2,
        secondaryOnly: ['RAIN:RED'],
      };
    },
    checkSourceGapRecoveryEvidence() {
      evidenceCalls += 1;
      return { status: 'DETAIL_CONFIRMED' };
    },
    checkSourceGapRecoveryFailover() {
      failoverCalls += 1;
      return { status: 'RECOVERED' };
    },
  });

  const result = context.runNotificationSupervisor();
  assert.equal(evidenceCalls, 1);
  assert.equal(failoverCalls, 1);
  assert.match(result.recovery, /DETAIL_CONFIRMED/);
  assert.match(result.recovery, /RECOVERED/);
});

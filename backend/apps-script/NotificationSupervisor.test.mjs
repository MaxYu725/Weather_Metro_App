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
    runNotificationFastPoll_() {
      return {
        mode: 'FULL',
        reason: 'BOOTSTRAP',
        primarySummary: { WHOT: { code: 'WHOT' } },
      };
    },
    checkWarningSourceRedundancy() {
      return {
        status: 'MATCH',
        checkedAtEpochMs: Date.now(),
        consecutiveSecondaryOnly: 0,
        secondaryOnly: [],
      };
    },
    checkWarningSourceRedundancyFromSummary_() {
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
    notificationFastPollVerification_() {
      return {
        schemaVersion: 2,
        fullRefreshIntervalMs: 170000,
        optimizedHydrationAvailable: true,
        committedSummaryDigestPresent: true,
        fullRefreshAgeMs: 10_000,
      };
    },
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

test('healthy source cross-check uses bounded steady-state cadence', () => {
  const { context } = loadScript();
  const now = 1_000_000;
  assert.equal(context.shouldRunSourceCrossCheck_(null, now, false), true);
  assert.equal(
    context.shouldRunSourceCrossCheck_({ status: 'MATCH', checkedAtEpochMs: now - 120_000 }, now, false),
    false,
  );
  assert.equal(
    context.shouldRunSourceCrossCheck_({ status: 'MATCH', checkedAtEpochMs: now - 180_000 }, now, false),
    true,
  );
});

test('degraded source parity is rechecked on the next minute', () => {
  const { context } = loadScript();
  const now = 1_000_000;
  assert.equal(
    context.shouldRunSourceCrossCheck_({ status: 'SECONDARY_ERROR', checkedAtEpochMs: now - 60_000 }, now, false),
    true,
  );
  assert.equal(
    context.shouldRunSourceCrossCheck_({ status: 'SECONDARY_ERROR', checkedAtEpochMs: now - 30_000 }, now, false),
    false,
  );
  assert.equal(
    context.shouldRunSourceCrossCheck_({ status: 'MATCH', checkedAtEpochMs: now - 10_000 }, now, true),
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

test('legacy trigger pruning is sampled rather than performed every minute', () => {
  const { context } = loadScript();
  assert.equal(context.shouldPruneLegacyNotificationTriggers_({ dayRunCount: 0 }), true);
  assert.equal(context.shouldPruneLegacyNotificationTriggers_({ dayRunCount: 1 }), false);
  assert.equal(context.shouldPruneLegacyNotificationTriggers_({ dayRunCount: 10 }), true);
});

test('consumer quota projection uses measured supervisor average runtime', () => {
  const { context } = loadScript();
  const telemetry = context.deriveNotificationSupervisorQuotaTelemetry_({
    dayKey: '2026-08-16',
    dayRunCount: 20,
    dayRuntimeMs: 60_000,
    fastJournalChecksToday: 13,
    fullJournalChecksToday: 7,
    journalFailuresToday: 0,
    sourceChecksToday: 7,
    recoveryChecksToday: 0,
    pruneChecksToday: 2,
    busySkipsToday: 0,
    componentFailuresToday: 0,
    legacyTriggersRemovedToday: 0,
    lastDurationMs: 2_000,
    maxDurationMs: 5_000,
    lastStatus: 'OK',
  });
  assert.equal(telemetry.averageRunMs, 3_000);
  assert.equal(telemetry.projectedDailyRuntimeMs, 4_320_000);
  assert.equal(telemetry.projectedRuntimeRisk, false);
  assert.equal(telemetry.consumerQuotaRisk, false);
  assert.equal(telemetry.fastJournalChecksToday, 13);
  assert.equal(telemetry.fullJournalChecksToday, 7);
});

test('steady-state supervisor uses fast-poll owner and optimized RSS cross-check', () => {
  let fastPollCalls = 0;
  let sourceCalls = 0;
  let evidenceCalls = 0;
  let failoverCalls = 0;
  const { context } = loadScript({
    runNotificationFastPoll_() {
      fastPollCalls += 1;
      return {
        mode: 'FAST',
        reason: 'WARNSUM_UNCHANGED',
        primarySummary: { WHOT: { code: 'WHOT' } },
      };
    },
    checkWarningSourceRedundancyFromSummary_() {
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
  assert.equal(result.journal, 'FAST');
  assert.equal(fastPollCalls, 1);
  assert.equal(sourceCalls, 1);
  assert.equal(evidenceCalls, 0);
  assert.equal(failoverCalls, 0);
});

test('journal fast-poll failure forces the independent source detector', () => {
  let sourceCalls = 0;
  const { context } = loadScript({
    runNotificationFastPoll_() { throw new Error('warnsum unavailable'); },
    readWarningSourceCrossCheck_() {
      return { status: 'MATCH', checkedAtEpochMs: Date.now() };
    },
    checkWarningSourceRedundancy() {
      sourceCalls += 1;
      return {
        status: 'MATCH',
        checkedAtEpochMs: Date.now(),
        consecutiveSecondaryOnly: 0,
        secondaryOnly: [],
      };
    },
  });
  const result = context.runNotificationSupervisor();
  assert.equal(result.status, 'DEGRADED');
  assert.equal(result.journal, 'ERROR');
  assert.equal(sourceCalls, 1);
});

test('persistent source gap immediately chains evidence then guarded failover', () => {
  let evidenceCalls = 0;
  let failoverCalls = 0;
  const { context } = loadScript({
    checkWarningSourceRedundancyFromSummary_() {
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

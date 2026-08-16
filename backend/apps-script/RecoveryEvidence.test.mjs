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
      computeDigest(_algorithm, value) {
        return [...crypto.createHash('sha256').update(String(value), 'utf8').digest()];
      },
      newBlob(value) {
        return { getBytes: () => [...Buffer.from(String(value), 'utf8')] };
      },
      base64EncodeWebSafe(value) {
        return Buffer.from(String(value), 'utf8').toString('base64url');
      },
    },
  };
  vm.createContext(context);
  for (const file of ['Code.gs', 'SourceRedundancy.gs', 'PipelineHealth.gs', 'RecoveryEvidence.gs']) {
    vm.runInContext(
      fs.readFileSync(new URL(`./${file}`, import.meta.url), 'utf8'),
      context,
      { filename: file },
    );
  }
  return context;
}

function response(code, body) {
  return {
    getResponseCode() { return code; },
    getContentText() { return body; },
  };
}

function detailedRss(...warnings) {
  return `<?xml version="1.0" encoding="UTF-8"?>
    <rss version="2.0"><channel><item><description><![CDATA[
      <p>${warnings.join('<br>')}</p>
    ]]></description></item></channel></rss>`;
}

function propertyStore(initial = {}) {
  const values = { ...initial };
  return {
    getProperty(key) { return Object.hasOwn(values, key) ? values[key] : null; },
    setProperty(key, value) { values[key] = String(value); return this; },
    values,
  };
}

test('detailed warning RSS preserves visible official warning names', () => {
  const script = loadScript();
  const text = script.parseDetailedWarningRss_(
    response(200, detailedRss('酷熱天氣警告', '紅色暴雨警告信號')),
  );
  assert.match(text, /酷熱天氣警告/);
  assert.deepEqual([...script.warningTokensFromText_(text)], ['HOT', 'RAIN:RED']);
});

test('detailed warning RSS rejects HTTP errors and empty responses', () => {
  const script = loadScript();
  assert.throws(() => script.parseDetailedWarningRss_(response(503, 'down')), /HTTP 503/);
  assert.throws(() => script.parseDetailedWarningRss_(response(200, '')), /empty body/);
});

test('recovery evidence storage is compact and round-trips', () => {
  const script = loadScript();
  const properties = propertyStore();
  const evidence = {
    schemaVersion: 1,
    status: 'DETAIL_CONFIRMED',
    requiredTokens: ['RAIN:RED'],
    detailTokens: ['HOT', 'RAIN:RED'],
    confirmedTokens: ['RAIN:RED'],
    missingTokens: [],
    detailDigest: 'abc',
  };
  script.writeSourceGapRecoveryEvidence_(properties, evidence);
  assert.deepEqual(
    JSON.parse(JSON.stringify(script.readSourceGapRecoveryEvidence_(properties))),
    evidence,
  );
  assert.equal(Buffer.byteLength(properties.values.HKO_SOURCE_GAP_RECOVERY_EVIDENCE_V1, 'utf8') < 4096, true);
});

test('source cross-check fallback reads the persisted health property', () => {
  const script = loadScript();
  delete script.readWarningSourceCrossCheck_;
  const properties = propertyStore({
    HKO_WARNING_SOURCE_CROSSCHECK_HEALTH_V1: JSON.stringify({
      status: 'SECONDARY_ONLY',
      secondaryOnly: ['RAIN:RED'],
      consecutiveSecondaryOnly: 2,
    }),
  });
  const value = script.readSourceCrossCheckForRecovery_(properties);
  assert.equal(value.status, 'SECONDARY_ONLY');
  assert.deepEqual([...value.secondaryOnly], ['RAIN:RED']);
});

test('journal cursor seeding repairs a health runtime installed after existing events', () => {
  const script = loadScript();
  const properties = propertyStore({
    HKO_NOTIFICATION_PIPELINE_RUNTIME_V1: JSON.stringify({
      schemaVersion: 1,
      latestJournalCursor: 0,
      lastAttemptEpochMs: 1,
    }),
  });
  script.PropertiesService = {
    getScriptProperties() { return properties; },
  };
  script.ensureJournalSheet_ = () => ({ getLastRow: () => 3 });
  const cursor = script.seedPipelineJournalCursorFromSheet_();
  assert.equal(cursor, 2);
  const runtime = JSON.parse(properties.values.HKO_NOTIFICATION_PIPELINE_RUNTIME_V1);
  assert.equal(runtime.latestJournalCursor, 2);
});

test('journal cursor seeding never moves the runtime cursor backwards', () => {
  const script = loadScript();
  const properties = propertyStore({
    HKO_NOTIFICATION_PIPELINE_RUNTIME_V1: JSON.stringify({
      schemaVersion: 1,
      latestJournalCursor: 9,
    }),
  });
  script.PropertiesService = {
    getScriptProperties() { return properties; },
  };
  script.ensureJournalSheet_ = () => ({ getLastRow: () => 3 });
  const cursor = script.seedPipelineJournalCursorFromSheet_();
  assert.equal(cursor, 2);
  const runtime = JSON.parse(properties.values.HKO_NOTIFICATION_PIPELINE_RUNTIME_V1);
  assert.equal(runtime.latestJournalCursor, 9);
});

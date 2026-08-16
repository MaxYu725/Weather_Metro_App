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
    RegExp,
    encodeURIComponent,
    warningTokensFromText_(value) {
      const text = String(value || '');
      const rules = [
        ['TC:8', /八號(?:東北|西北|東南|西南)?烈風或暴風信號/],
        ['RAIN:RED', /紅色暴雨警告信號/],
        ['HOT', /酷熱天氣警告/],
        ['FIRE', /火災危險警告/],
      ];
      return rules.filter(([, pattern]) => pattern.test(text)).map(([token]) => token).sort();
    },
    digest_(value) {
      return `digest:${String(value)}`;
    },
    severity_(code) {
      if (/^TC8/.test(code) || code === 'WRAINR') return 'URGENT';
      if (code === 'WFIRER') return 'WARNING';
      return 'ADVISORY';
    },
    channelFor_(severity) {
      return severity === 'URGENT' ? 'weather_alert_urgent' : 'weather_alert_general';
    },
  };
  vm.createContext(context);
  vm.runInContext(
    fs.readFileSync(new URL('./RecoveryFailover.gs', import.meta.url), 'utf8'),
    context,
    { filename: 'RecoveryFailover.gs' },
  );
  return context;
}

function healthyGap(now = 1_000_000) {
  return {
    source: {
      status: 'SECONDARY_ONLY',
      checkedAtEpochMs: now - 20_000,
      consecutiveSecondaryOnly: 2,
      secondaryOnly: ['RAIN:RED'],
      primaryOnly: [],
      primaryTokens: [],
      secondaryTokens: ['RAIN:RED'],
      primaryOk: true,
      secondaryOk: true,
    },
    evidence: {
      status: 'DETAIL_CONFIRMED',
      checkedAtEpochMs: now - 10_000,
      sourceGapStreak: 2,
      confirmedTokens: ['RAIN:RED'],
      detailDigest: 'abc123',
    },
  };
}

test('requires a fresh persistent gap confirmed by detailed RSS', () => {
  const script = loadScript();
  const { source, evidence } = healthyGap();
  const result = script.recoveryFailoverEligibility_(source, evidence, 1_000_000);
  assert.equal(result.eligible, true);
  assert.deepEqual([...result.tokens], ['RAIN:RED']);
});

test('does not recover a transient or stale mismatch', () => {
  const script = loadScript();
  const first = healthyGap();
  first.source.consecutiveSecondaryOnly = 1;
  assert.equal(script.recoveryFailoverEligibility_(first.source, first.evidence, 1_000_000).eligible, false);

  const stale = healthyGap();
  stale.evidence.checkedAtEpochMs = 1_000_000 - (4 * 60 * 1000);
  const result = script.recoveryFailoverEligibility_(stale.source, stale.evidence, 1_000_000);
  assert.equal(result.eligible, false);
  assert.equal(result.status, 'EVIDENCE_STALE');
});

test('detailed evidence must confirm the currently secondary-only token', () => {
  const script = loadScript();
  const { source, evidence } = healthyGap();
  evidence.confirmedTokens = ['HOT'];
  const result = script.recoveryFailoverEligibility_(source, evidence, 1_000_000);
  assert.equal(result.eligible, false);
  assert.equal(result.status, 'TOKEN_CONFIRMATION_MISSING');
});

test('extracts the official RSS item body for the missing warning', () => {
  const script = loadScript();
  const xml = `
    <rss><channel>
      <item><title><![CDATA[酷熱天氣警告]]></title><description><![CDATA[酷熱天氣警告現正生效。]]></description><pubDate>Sun, 16 Aug 2026 05:00:00 GMT</pubDate></item>
      <item><title><![CDATA[紅色暴雨警告信號]]></title><description><![CDATA[紅色暴雨警告信號現正生效。<br/>市民應提高警覺。]]></description><pubDate>Sun, 16 Aug 2026 06:00:00 GMT</pubDate></item>
    </channel></rss>`;
  const result = script.extractRecoveryDetailForToken_(xml, 'fallback', 'RAIN:RED');
  assert.equal(result.titleText, '紅色暴雨警告信號');
  assert.equal(result.body, '紅色暴雨警告信號現正生效。\n市民應提高警覺。');
  assert.equal(result.sourceTime, 'Sun, 16 Aug 2026 06:00:00 GMT');
});

test('preserves directional No. 8 and red fire warning identity', () => {
  const script = loadScript();
  const tc = script.recoveryMetadataForToken_('TC:8', '八號東南烈風或暴風信號現正生效');
  assert.equal(tc.code, 'TC8SE');
  assert.equal(tc.title, '八號東南烈風或暴風信號');
  assert.equal(tc.severity, 'URGENT');

  const fire = script.recoveryMetadataForToken_('FIRE', '紅色火災危險警告現正生效');
  assert.equal(fire.code, 'WFIRER');
  assert.equal(fire.title, '紅色火災危險警告');
  assert.equal(fire.severity, 'WARNING');
});

test('recovery journal event contains official body and safe internal routing', () => {
  const script = loadScript();
  const event = script.buildRecoveryJournalEvent_(
    'RAIN:RED',
    { code: 'WRAINR', title: '紅色暴雨警告信號', severity: 'URGENT' },
    '官方詳細警告內容\n第二行',
    'Sun, 16 Aug 2026 06:00:00 GMT',
    'hko:recovery:abc',
    1_000_000,
    9,
  );
  assert.equal(event.body, '官方詳細警告內容\n第二行');
  assert.equal(event.eventKind, 'RECOVERY');
  assert.equal(event.sourceType, 'RSS_RECOVERY');
  assert.equal(event.channel, 'weather_alert_urgent');
  assert.equal(event.journalCursor, 9);
  assert.match(event.target, /^weathermetro:\/\/current\/alerts\?/);
});

test('sent-state resets only after both official active-warning views drop the token', () => {
  const script = loadScript();
  const state = {
    schemaVersion: 1,
    tokens: {
      'RAIN:RED': { eventId: 'one' },
      HOT: { eventId: 'two' },
    },
  };
  const source = {
    primaryOk: true,
    secondaryOk: true,
    primaryTokens: ['HOT'],
    secondaryTokens: ['HOT'],
  };
  const pruned = script.pruneRecoveryFailoverSentState_(state, source);
  assert.deepEqual(Object.keys(pruned.tokens), ['HOT']);

  const degraded = script.pruneRecoveryFailoverSentState_(state, {
    primaryOk: false,
    secondaryOk: true,
    primaryTokens: [],
    secondaryTokens: [],
  });
  assert.deepEqual(Object.keys(degraded.tokens).sort(), ['HOT', 'RAIN:RED']);
});

test('event id changes when the official detailed RSS publication changes', () => {
  const script = loadScript();
  assert.notEqual(
    script.recoveryFailoverEventId_('RAIN:RED', 'digest-a'),
    script.recoveryFailoverEventId_('RAIN:RED', 'digest-b'),
  );
});

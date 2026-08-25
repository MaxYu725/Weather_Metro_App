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
    encodeURIComponent,
    ScriptApp: {
      getService() {
        return { getUrl: () => 'https://script.google.com/macros/s/test/exec' };
      },
    },
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
  const code = fs.readFileSync(new URL('./Code.gs', import.meta.url), 'utf8');
  const journal = fs.readFileSync(new URL('./Journal.gs', import.meta.url), 'utf8');
  vm.runInContext(code, context, { filename: 'Code.gs' });
  vm.runInContext(journal, context, { filename: 'Journal.gs' });
  return context;
}

function summary(actionCode = 'ISSUE', updateTime = '2026-08-16T10:00:00+08:00') {
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

function detail(body = '第一段\n第二行') {
  return {
    details: [{
      warningStatementCode: 'WHOT',
      contents: [body],
      updateTime: '2026-08-16T10:00:00+08:00',
    }],
  };
}

function fakeSheet(events) {
  const rows = [
    ['cursor', 'eventId', 'eventJson', 'journalledAtEpochMs'],
    ...events.map((event) => [
      String(event.journalCursor),
      event.eventId,
      JSON.stringify(event),
      String(event.sentAtEpochMillis),
    ]),
  ];
  return {
    getLastRow() { return rows.length; },
    getRange(row, column, rowCount, columnCount) {
      return {
        getDisplayValues() {
          return rows.slice(row - 1, row - 1 + rowCount).map((source) =>
            source.slice(column - 1, column - 1 + columnCount));
        },
      };
    },
  };
}

test('journal keeps the complete HKO body while the FCM body is only a preview', () => {
  const script = loadScript();
  const longBody = '香港天文台完整警告內容。'.repeat(800);
  const publications = script.normaliseJournalPublications_(summary(), detail(longBody), { swt: [] });
  assert.equal(publications.length, 1);
  assert.equal(publications[0].body, longBody);
  assert.ok(Buffer.byteLength(publications[0].body, 'utf8') > 900);

  const event = script.journalEventForPublication_(publications[0], 1234);
  event.journalCursor = 7;
  const queued = script.enqueueJournalEvents_([], [event], 1234);
  assert.equal(queued.length, 1);
  assert.ok(Buffer.byteLength(queued[0].message.body, 'utf8') <= 900);
  assert.equal(queued[0].message.bodyTruncated, 'true');
  assert.equal(queued[0].message.journalCursor, '7');
  assert.equal(queued[0].message.journalUrl, 'https://script.google.com/macros/s/test/exec');
});

test('journal FCM combines a system notification with durable reconciliation data', () => {
  const script = loadScript();
  const event = script.journalEventForPublication_(
    script.normaliseJournalPublications_(
      summary(),
      detail('香港天文台完整警告內容。'.repeat(800)),
      { swt: [] },
    )[0],
    1234,
  );
  event.journalCursor = 7;
  const message = script.enqueueJournalEvents_([], [event], 1234)[0].message;
  const payload = script.buildJournalFcmPayload_(message);

  assert.equal(payload.message.notification.title, message.title);
  assert.ok(Buffer.byteLength(payload.message.notification.body, 'utf8') <= 300);
  assert.equal(payload.message.data.title, undefined);
  assert.equal(payload.message.data.body, undefined);
  assert.equal(payload.message.data.journalCursor, '7');
  assert.equal(payload.message.data.journalUrl, 'https://script.google.com/macros/s/test/exec');
  assert.equal(payload.message.android.priority, 'HIGH');
  assert.equal(payload.message.android.notification.channelId, message.channel);
  assert.equal(payload.message.android.notification.tag, message.eventId);
  assert.ok(Buffer.byteLength(JSON.stringify(payload), 'utf8') < 2048);
});

test('official action and time remain part of journal publication identity', () => {
  const script = loadScript();
  const issued = script.normaliseJournalPublications_(
    summary('ISSUE', '2026-08-16T10:00:00+08:00'),
    detail(),
    { swt: [] },
  )[0];
  const extended = script.normaliseJournalPublications_(
    summary('EXTEND', '2026-08-16T11:00:00+08:00'),
    detail(),
    { swt: [] },
  )[0];
  assert.notEqual(issued.id, extended.id);
  assert.equal(extended.actionCode, 'EXTEND');
});

test('WTCPRE8 uses the official title and duplicate SWT text is not notified twice', () => {
  const script = loadScript();
  const body = '天文台將考慮在下午四時至六時之間改發八號烈風或暴風信號。';
  const publications = script.normaliseJournalPublications_({}, {
    details: [{
      warningStatementCode: 'WTCPRE8',
      contents: [body],
      updateTime: '2026-08-16T14:00:00+08:00',
    }],
  }, {
    swt: [{ desc: body, updateTime: '2026-08-16T14:00:00+08:00' }],
  });
  assert.equal(publications.length, 1);
  assert.equal(publications[0].code, 'WTCPRE8');
  assert.equal(publications[0].title, '預警八號熱帶氣旋警告信號特別報告');
});

test('journal state stores only small publication metadata, not the full body', () => {
  const script = loadScript();
  const body = '完整內容'.repeat(5000);
  const publication = script.normaliseJournalPublications_(summary(), detail(body), { swt: [] })[0];
  const state = script.journalStateForPublications_([publication]);
  const serialized = JSON.stringify(state[publication.id]);
  assert.equal(serialized.includes(body.slice(0, 100)), false);
  assert.ok(Buffer.byteLength(serialized, 'utf8') < 1024);
});

test('journal page returns every event after the cursor in order without aggregation', () => {
  const script = loadScript();
  const events = [1, 2, 3].map((cursor) => ({
    eventId: `hko:event-${cursor}`,
    title: `公告 ${cursor}`,
    body: `內容 ${cursor}`,
    channel: 'weather_alert_general',
    target: 'weathermetro://current/alerts',
    alertId: `warning:${cursor}`,
    alertCode: String(cursor),
    eventKind: 'ISSUE',
    sourceType: 'WARNING',
    sourceTime: `2026-08-16T1${cursor}:00:00+08:00`,
    sentAtEpochMillis: 1000 + cursor,
    journalCursor: cursor,
  }));
  const page = script.readJournalPage_(fakeSheet(events), 1, 1);
  assert.equal(page.events.length, 1);
  assert.equal(page.events[0].eventId, 'hko:event-2');
  assert.equal(page.nextCursor, 2);
  assert.equal(page.latestCursor, 3);
  assert.equal(page.hasMore, true);
});

test('journal page is empty and stable when client is already at the latest cursor', () => {
  const script = loadScript();
  const event = {
    eventId: 'hko:event-1',
    title: '公告',
    body: '內容',
    journalCursor: 1,
    sentAtEpochMillis: 1,
  };
  const page = script.readJournalPage_(fakeSheet([event]), 1, 100);
  assert.deepEqual(JSON.parse(JSON.stringify(page)), {
    events: [],
    nextCursor: 1,
    latestCursor: 1,
    hasMore: false,
  });
});

test('journal FCM queue remains deterministic for server retries', () => {
  const script = loadScript();
  const publication = script.normaliseJournalPublications_(summary(), detail(), { swt: [] })[0];
  const event = script.journalEventForPublication_(publication, 1234);
  event.journalCursor = 1;
  const once = script.enqueueJournalEvents_([], [event], 1234);
  const twice = script.enqueueJournalEvents_(once, [event], 5678);
  assert.equal(twice.length, 1);
  assert.equal(twice[0].id, event.eventId);
  assert.equal(twice[0].message.schemaVersion, '4');
});

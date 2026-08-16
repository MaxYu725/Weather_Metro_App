import assert from 'node:assert/strict';
import fs from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';

test('soak reset deletes only supervisor runtime telemetry', () => {
  const values = new Map([
    ['HKO_NOTIFICATION_SUPERVISOR_RUNTIME_V2', '{"dayRunCount":41}'],
    ['HKO_NOTIFICATION_FAST_POLL_V2', '{"schemaVersion":2}'],
    ['HKO_NOTIFICATION_JOURNAL_SPREADSHEET_ID', 'sheet-id'],
    ['HKO_NOTIFICATION_OUTBOX_INDEX_V4', '["event"]'],
  ]);
  const deleted = [];
  const context = {
    console,
    Date,
    JSON,
    NOTIFICATION_SUPERVISOR_CONFIG: {
      runtimePropertyKey: 'HKO_NOTIFICATION_SUPERVISOR_RUNTIME_V2',
    },
    PropertiesService: {
      getScriptProperties() {
        return {
          deleteProperty(key) {
            deleted.push(key);
            values.delete(key);
          },
        };
      },
    },
  };
  vm.createContext(context);
  vm.runInContext(
    fs.readFileSync(new URL('./NotificationQuotaTelemetry.gs', import.meta.url), 'utf8'),
    context,
    { filename: 'NotificationQuotaTelemetry.gs' },
  );

  const result = context.resetNotificationSupervisorSoakTelemetry();
  assert.deepEqual(deleted, ['HKO_NOTIFICATION_SUPERVISOR_RUNTIME_V2']);
  assert.equal(values.has('HKO_NOTIFICATION_SUPERVISOR_RUNTIME_V2'), false);
  assert.equal(values.get('HKO_NOTIFICATION_FAST_POLL_V2'), '{"schemaVersion":2}');
  assert.equal(values.get('HKO_NOTIFICATION_JOURNAL_SPREADSHEET_ID'), 'sheet-id');
  assert.equal(values.get('HKO_NOTIFICATION_OUTBOX_INDEX_V4'), '["event"]');
  assert.equal(result.dayRunCount, 0);
  assert.equal(result.dayRuntimeMs, 0);
  assert.equal(result.durableNotificationStateUntouched, true);
});

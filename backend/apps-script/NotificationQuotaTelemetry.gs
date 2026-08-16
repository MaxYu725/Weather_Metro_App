/**
 * Owner-only helper for starting a clean notification runtime soak.
 *
 * This deletes only the supervisor runtime telemetry property. It does not
 * touch the durable HKO journal, publication/source state, FCM outbox, journal
 * cursor, fast-poll digest, recovery evidence, or trigger configuration.
 *
 * Use this after deploying a runtime optimization when a clean before/after
 * measurement is required. The next supervisor execution starts at run 1.
 */
function resetNotificationSupervisorSoakTelemetry() {
  const properties = PropertiesService.getScriptProperties();
  const runtimeKey = (
    typeof NOTIFICATION_SUPERVISOR_CONFIG !== 'undefined' &&
    NOTIFICATION_SUPERVISOR_CONFIG.runtimePropertyKey
  )
    ? NOTIFICATION_SUPERVISOR_CONFIG.runtimePropertyKey
    : 'HKO_NOTIFICATION_SUPERVISOR_RUNTIME_V2';

  properties.deleteProperty(runtimeKey);

  const result = {
    schemaVersion: 1,
    resetAtEpochMs: Date.now(),
    runtimePropertyKey: runtimeKey,
    dayRunCount: 0,
    dayRuntimeMs: 0,
    durableNotificationStateUntouched: true,
  };
  console.log(JSON.stringify(result));
  return result;
}

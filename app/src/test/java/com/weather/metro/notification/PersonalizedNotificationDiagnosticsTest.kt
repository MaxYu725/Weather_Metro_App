package com.weather.metro.notification

import org.junit.Assert.assertEquals
import org.junit.Test

class PersonalizedNotificationDiagnosticsTest {
    @Test
    fun `disabled schedule is healthy only when no periodic work remains active`() {
        assertEquals(
            PersonalizedNotificationDiagnosticVerdict.DISABLED,
            assessPersonalizedNotificationDiagnostics(
                gate(scheduleExpected = false, periodicActiveCount = 0),
            ),
        )
        assertEquals(
            PersonalizedNotificationDiagnosticVerdict.STOPPING_OR_STALE_WORK,
            assessPersonalizedNotificationDiagnostics(
                gate(scheduleExpected = false, periodicActiveCount = 1),
            ),
        )
    }

    @Test
    fun `expected schedule requires a fresh cached host location`() {
        assertEquals(
            PersonalizedNotificationDiagnosticVerdict.LOCATION_UNAVAILABLE,
            assessPersonalizedNotificationDiagnostics(
                gate(locationAvailable = false, locationFresh = false),
            ),
        )
        assertEquals(
            PersonalizedNotificationDiagnosticVerdict.LOCATION_STALE,
            assessPersonalizedNotificationDiagnostics(
                gate(locationAvailable = true, locationFresh = false),
            ),
        )
    }

    @Test
    fun `expected schedule requires exactly one active periodic work spec`() {
        assertEquals(
            PersonalizedNotificationDiagnosticVerdict.PERIODIC_MISSING,
            assessPersonalizedNotificationDiagnostics(gate(periodicActiveCount = 0)),
        )
        assertEquals(
            PersonalizedNotificationDiagnosticVerdict.PERIODIC_DUPLICATE,
            assessPersonalizedNotificationDiagnostics(gate(periodicActiveCount = 2)),
        )
    }

    @Test
    fun `periodic work must retain both dispatch flags after upgrade`() {
        assertEquals(
            PersonalizedNotificationDiagnosticVerdict.PERIODIC_DISPATCH_INVALID,
            assessPersonalizedNotificationDiagnostics(
                gate(periodicDispatchHeavyRain = true, periodicDispatchPersonalizedRain = false),
            ),
        )
        assertEquals(
            PersonalizedNotificationDiagnosticVerdict.READY,
            assessPersonalizedNotificationDiagnostics(gate()),
        )
    }

    @Test
    fun `SWIRLS durable runtime error is surfaced only for an active error state`() {
        assertEquals(
            "SWIRLS runtime · HTTP 503 from radar.max-yu.workers.dev",
            personalizedRainDiagnosticError(
                status = "ERROR",
                lastError = "HTTP 503 from radar.max-yu.workers.dev",
            ),
        )
        assertEquals(
            "",
            personalizedRainDiagnosticError(
                status = "EVALUATED",
                lastError = "old transient failure",
            ),
        )
        assertEquals("", personalizedRainDiagnosticError(status = "ERROR", lastError = ""))
    }

    private fun gate(
        scheduleExpected: Boolean = true,
        locationAvailable: Boolean = true,
        locationFresh: Boolean = true,
        periodicActiveCount: Int = 1,
        periodicDispatchHeavyRain: Boolean = true,
        periodicDispatchPersonalizedRain: Boolean = true,
    ) = PersonalizedNotificationDiagnosticGate(
        scheduleExpected = scheduleExpected,
        locationAvailable = locationAvailable,
        locationFresh = locationFresh,
        periodicActiveCount = periodicActiveCount,
        periodicDispatchHeavyRain = periodicDispatchHeavyRain,
        periodicDispatchPersonalizedRain = periodicDispatchPersonalizedRain,
    )
}

package com.weather.metro.notification

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Process-local serialization for the personalised-rain durable state machine.
 *
 * WorkManager may overlap the shared 15-minute cadence with an immediate refresh. Both execute in
 * Weather Metro's default app process, so serializing the SWIRLS runtime here prevents concurrent
 * read/stage/publish/commit operations against the same local episode state.
 */
internal object PersonalizedRainExecutionLock {
    private val mutex = Mutex()

    suspend fun <T> withLock(block: suspend () -> T): T = mutex.withLock { block() }
}

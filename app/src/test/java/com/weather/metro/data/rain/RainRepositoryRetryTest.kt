package com.weather.metro.data.rain

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RainRepositoryRetryTest {
    @Test
    fun retriesInitialSwirlsFrameOnceBeforeFallingBack() = runBlocking {
        var attempts = 0

        val value = loadInitialSwirlsWithRetry(
            maxAttempts = 2,
            retryDelayMs = 0,
        ) {
            attempts += 1
            if (attempts == 1) error("transient SWIRLS failure")
            "frame-0"
        }

        assertEquals(2, attempts)
        assertEquals("frame-0", value)
    }

    @Test
    fun returnsSecondFailureAfterBoundedRetry() = runBlocking {
        var attempts = 0
        val secondFailure = IllegalStateException("second failure")

        val result = runCatching {
            loadInitialSwirlsWithRetry(
                maxAttempts = 2,
                retryDelayMs = 0,
            ) {
                attempts += 1
                if (attempts == 1) error("first failure")
                throw secondFailure
            }
        }

        assertEquals(2, attempts)
        assertSame(secondFailure, result.exceptionOrNull())
    }

    @Test
    fun cancellationNeverRetries() {
        var attempts = 0
        val result = runCatching {
            runBlocking {
                loadInitialSwirlsWithRetry(
                    maxAttempts = 2,
                    retryDelayMs = 0,
                ) {
                    attempts += 1
                    throw CancellationException("cancel")
                }
            }
        }

        assertEquals(1, attempts)
        assertTrue(result.exceptionOrNull() is CancellationException)
    }
}

package com.weather.metro.ui.map

import org.junit.Assert.assertEquals
import org.junit.Test

class HongKongBackdropTest {
    @Test
    fun portraitSnapshotCapsLongEdgeAndPreservesAspectRatio() {
        assertEquals(
            BackdropSnapshotSize(width = 720, height = 1_600),
            backdropSnapshotSize(width = 1_080, height = 2_400),
        )
    }

    @Test
    fun landscapeSnapshotCapsLongEdgeAndPreservesAspectRatio() {
        assertEquals(
            BackdropSnapshotSize(width = 1_600, height = 720),
            backdropSnapshotSize(width = 2_400, height = 1_080),
        )
    }

    @Test
    fun ordinarySizeIsNotUpscaled() {
        assertEquals(
            BackdropSnapshotSize(width = 800, height = 1_280),
            backdropSnapshotSize(width = 800, height = 1_280),
        )
    }

    @Test
    fun missingLayoutHasNoSnapshot() {
        assertEquals(
            BackdropSnapshotSize(width = 0, height = 0),
            backdropSnapshotSize(width = 0, height = 1_280),
        )
    }
}

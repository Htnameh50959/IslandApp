package com.staad.island

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaPositionTest {

    @Test
    fun pausedStaysAtStoredPosition() {
        assertEquals(4_000, positionAt(4_000, playing = false, 10_000, 1f, 180_000))
    }

    @Test
    fun playingAdvancesWithElapsedTime() {
        assertEquals(14_000, positionAt(4_000, playing = true, 10_000, 1f, 180_000))
    }

    @Test
    fun speedScalesAdvance() {
        assertEquals(19_000, positionAt(4_000, playing = true, 5_000, 3f, 180_000))
    }

    @Test
    fun clampsToDuration() {
        assertEquals(180_000, positionAt(179_000, playing = true, 10_000, 1f, 180_000))
    }

    @Test
    fun unknownDurationDoesNotClamp() {
        assertEquals(1_000_000, positionAt(999_000, playing = true, 1_000, 1f, 0))
    }

    @Test
    fun negativeStoredPositionIsZero() {
        assertEquals(0, positionAt(-5_000, playing = true, 1_000, 1f, 180_000))
    }

    @Test
    fun backwardsClockDoesNotGoNegative() {
        assertEquals(0, positionAt(0, playing = true, -2_000, 1f, 180_000))
    }
}

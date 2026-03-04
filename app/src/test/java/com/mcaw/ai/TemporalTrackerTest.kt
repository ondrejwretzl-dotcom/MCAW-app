package com.mcaw.ai

import com.mcaw.model.Box
import com.mcaw.model.Detection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TemporalTrackerTest {

    @Test
    fun lockGrace_keepsLockAcrossShortDropoutAndRecovery() {
        val tracker = TemporalTracker(lockGraceMs = 400L, lockGraceMaxMissFrames = 3)
        var ts = 0L

        repeat(10) {
            val det = det(100f, 100f, 220f, 260f)
            tracker.update(listOf(det), listOf(det), tsMs = ts)
            ts += 33L
        }
        val lockId = tracker.getLockedTrackId()
        assertNotNull(lockId)

        repeat(3) {
            tracker.update(emptyList(), emptyList(), tsMs = ts)
            ts += 33L
        }
        assertEquals(lockId, tracker.getLockedTrackId())
        assertTrue(tracker.isLockGraceActive(ts))

        val recovered = det(102f, 102f, 222f, 262f)
        tracker.update(listOf(recovered), listOf(recovered), tsMs = ts)
        assertEquals(lockId, tracker.getLockedTrackId())
        assertEquals(0, tracker.getLockedMissFrames())
    }

    @Test
    fun lockGrace_expiresAndSwitchesToNewTarget() {
        val tracker = TemporalTracker(lockGraceMs = 200L, lockGraceMaxMissFrames = 3)
        var ts = 0L

        repeat(5) {
            val det = det(100f, 100f, 220f, 260f)
            tracker.update(listOf(det), listOf(det), tsMs = ts)
            ts += 40L
        }
        val firstLock = tracker.getLockedTrackId()

        repeat(4) {
            tracker.update(emptyList(), emptyList(), tsMs = ts)
            ts += 80L
        }

        val newcomer = det(360f, 110f, 500f, 300f, score = 0.98f)
        tracker.update(listOf(newcomer), listOf(newcomer), tsMs = ts)

        assertNotNull(tracker.getLockedTrackId())
        assertNotEquals(firstLock, tracker.getLockedTrackId())
    }

    @Test
    fun switchConfirmation_ignoresSingleFrameSpike() {
        val tracker = TemporalTracker(switchConfirmFrames = 3, switchMargin = 0.08f)
        var ts = 0L

        repeat(6) {
            val det = det(100f, 100f, 220f, 260f, score = 0.90f)
            tracker.update(listOf(det), listOf(det), tsMs = ts)
            ts += 33L
        }
        val lockId = tracker.getLockedTrackId()

        val stable = det(102f, 100f, 222f, 260f, score = 0.90f)
        val spike = det(300f, 90f, 430f, 280f, score = 0.99f)
        tracker.update(listOf(stable, spike), listOf(stable, spike), tsMs = ts)
        ts += 33L

        val backToStable = det(104f, 100f, 224f, 260f, score = 0.90f)
        tracker.update(listOf(backToStable), listOf(backToStable), tsMs = ts)

        assertEquals(lockId, tracker.getLockedTrackId())
    }

    @Test
    fun switchConfirmation_switchesAfterStableBetterCandidate() {
        val tracker = TemporalTracker(switchConfirmFrames = 3, switchMargin = 0.08f)
        var ts = 0L

        repeat(6) {
            val det = det(100f, 100f, 220f, 260f, score = 0.90f)
            tracker.update(listOf(det), listOf(det), tsMs = ts)
            ts += 33L
        }
        val firstLockId = tracker.getLockedTrackId()

        repeat(3) {
            val current = det(102f, 100f, 222f, 260f, score = 0.90f)
            val better = det(300f, 90f, 430f, 280f, score = 0.99f)
            tracker.update(listOf(current, better), listOf(current, better), tsMs = ts)
            ts += 33L
        }

        assertEquals(TemporalTracker.SwitchReason.BETTER_STABLE, tracker.getLastSwitchReason())
        assertNotNull(tracker.getLockedTrackId())
        assertNotEquals(firstLockId, tracker.getLockedTrackId())
    }

    @Test
    fun occlusionFallback_matchesLowIouLargeBoxWhenEnabled() {
        val tracker = TemporalTracker(iouMatchThreshold = 0.2f)
        var ts = 0L

        repeat(4) {
            val det = det(100f, 120f, 240f, 320f)
            tracker.update(listOf(det), listOf(det), tsMs = ts)
            ts += 33L
        }
        val lockId = tracker.getLockedTrackId()

        val occluded = det(110f, 170f, 236f, 350f)
        tracker.update(listOf(occluded), listOf(occluded), tsMs = ts, bottomOccluded = true)

        assertEquals(lockId, tracker.getLockedTrackId())
        assertTrue(tracker.wasLastMatchOcclusionFallback())
    }

    @Test
    fun occlusionFallback_doesNotMatchSmallNoisyBoxes() {
        val tracker = TemporalTracker(iouMatchThreshold = 0.2f)
        var ts = 0L

        repeat(4) {
            val det = det(100f, 120f, 130f, 150f, score = 0.95f)
            tracker.update(listOf(det), listOf(det), tsMs = ts)
            ts += 33L
        }

        val noisy = det(112f, 142f, 138f, 168f, score = 0.95f)
        tracker.update(listOf(noisy), listOf(noisy), tsMs = ts, bottomOccluded = true)

        assertFalse(tracker.wasLastMatchOcclusionFallback())
        assertEquals(1, tracker.getLockedMissFrames())
    }

    @Test
    fun rejectNewWideDetections_doesNotCreateNewTrack() {
        val tracker = TemporalTracker()
        val wide = Detection(box = Box(10f, 10f, 210f, 90f), score = 0.95f, label = "car")

        val out = tracker.update(listOf(wide), listOf(wide), tsMs = 0L)

        assertEquals(0, out.size)
        assertEquals(1, tracker.getLastRejectedNewWideCount())
    }

    private fun det(x1: Float, y1: Float, x2: Float, y2: Float, score: Float = 0.95f): Detection =
        Detection(box = Box(x1, y1, x2, y2), score = score, label = "car")
}

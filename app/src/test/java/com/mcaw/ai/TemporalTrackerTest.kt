package com.mcaw.ai

import com.mcaw.model.Box
import com.mcaw.model.Detection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TemporalTrackerTest {

    @Test
    fun lockGrace_keepsLockAcrossShortDropoutAndRecovery() {
        val tracker = TemporalTracker(lockGraceMs = 400L, lockGraceMaxMissFrames = 3)
        var ts = 0L

        repeat(10) {
            tracker.update(listOf(det(100f, 100f, 220f, 260f)), tsMs = ts)
            ts += 33L
        }
        val lockId = tracker.getLockedTrackId()
        assertTrue(lockId != null)

        repeat(3) {
            tracker.update(emptyList(), tsMs = ts)
            ts += 33L
        }
        assertEquals(lockId, tracker.getLockedTrackId())
        assertTrue(tracker.isLockGraceActive(ts))

        tracker.update(listOf(det(102f, 102f, 222f, 262f)), tsMs = ts)
        assertEquals(lockId, tracker.getLockedTrackId())
        assertEquals(0, tracker.getLockedMissFrames())
    }

    @Test
    fun lockGrace_expiresAndSwitchesToNewTarget() {
        val tracker = TemporalTracker(lockGraceMs = 200L, lockGraceMaxMissFrames = 3)
        var ts = 0L

        repeat(5) {
            tracker.update(listOf(det(100f, 100f, 220f, 260f)), tsMs = ts)
            ts += 40L
        }
        val firstLock = tracker.getLockedTrackId()

        repeat(4) {
            tracker.update(emptyList(), tsMs = ts)
            ts += 80L
        }
        tracker.update(listOf(det(360f, 110f, 500f, 300f, score = 0.98f)), tsMs = ts)

        assertTrue(tracker.getLockedTrackId() != null)
        assertTrue(tracker.getLockedTrackId() != firstLock)
    }

    @Test
    fun switchConfirmation_ignoresSingleFrameSpike() {
        val tracker = TemporalTracker(switchConfirmFrames = 3, switchMargin = 0.08f)
        var ts = 0L

        repeat(6) {
            tracker.update(listOf(det(100f, 100f, 220f, 260f, score = 0.90f)), tsMs = ts)
            ts += 33L
        }
        val lockId = tracker.getLockedTrackId()

        tracker.update(
            listOf(
                det(102f, 100f, 222f, 260f, score = 0.90f),
                det(300f, 90f, 430f, 280f, score = 0.99f)
            ),
            tsMs = ts
        )
        ts += 33L
        tracker.update(listOf(det(104f, 100f, 224f, 260f, score = 0.90f)), tsMs = ts)

        assertEquals(lockId, tracker.getLockedTrackId())
    }

    @Test
    fun switchConfirmation_switchesAfterStableBetterCandidate() {
        val tracker = TemporalTracker(switchConfirmFrames = 3, switchMargin = 0.08f)
        var ts = 0L

        repeat(6) {
            tracker.update(listOf(det(100f, 100f, 220f, 260f, score = 0.90f)), tsMs = ts)
            ts += 33L
        }

        repeat(3) {
            tracker.update(
                listOf(
                    det(102f, 100f, 222f, 260f, score = 0.90f),
                    det(300f, 90f, 430f, 280f, score = 0.99f)
                ),
                tsMs = ts
            )
            ts += 33L
        }

        assertEquals(TemporalTracker.SwitchReason.BETTER_STABLE, tracker.getLastSwitchReason())
    }

    @Test
    fun occlusionFallback_matchesLowIouLargeBoxWhenEnabled() {
        val tracker = TemporalTracker(iouMatchThreshold = 0.2f)
        var ts = 0L

        repeat(4) {
            tracker.update(listOf(det(100f, 120f, 240f, 320f)), tsMs = ts)
            ts += 33L
        }
        val lockId = tracker.getLockedTrackId()

        tracker.update(
            listOf(det(110f, 170f, 236f, 350f)),
            tsMs = ts,
            bottomOccluded = true
        )

        assertEquals(lockId, tracker.getLockedTrackId())
        assertTrue(tracker.wasLastMatchOcclusionFallback())
    }

    @Test
    fun occlusionFallback_doesNotMatchSmallNoisyBoxes() {
        val tracker = TemporalTracker(iouMatchThreshold = 0.2f)
        var ts = 0L

        repeat(4) {
            tracker.update(listOf(det(100f, 120f, 130f, 150f, score = 0.95f)), tsMs = ts)
            ts += 33L
        }

        tracker.update(
            listOf(det(112f, 142f, 138f, 168f, score = 0.95f)),
            tsMs = ts,
            bottomOccluded = true
        )

        assertFalse(tracker.wasLastMatchOcclusionFallback())
        assertEquals(1, tracker.getLockedMissFrames())
    }

    private fun det(x1: Float, y1: Float, x2: Float, y2: Float, score: Float = 0.95f): Detection =
        Detection(box = Box(x1, y1, x2, y2), score = score, label = "car")
}

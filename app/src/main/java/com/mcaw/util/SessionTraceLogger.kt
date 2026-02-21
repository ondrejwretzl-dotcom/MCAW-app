package com.mcaw.util

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import java.util.ArrayDeque

class SessionTraceLogger(
    private val context: Context,
    private val fileName: String,
    prealloc: Int = 64
) {
    private class TrackEvent {
        var tsMs: Long = 0L
        var lockedId: Long = -1L
        var lockedAgeFrames: Int = 0
        var lockedMisses: Int = 0
        var bestId: Long = -1L
        var bestScore: Float = 0f
        var lockedScore: Float = 0f
        var switchPending: Int = 0
        var switchCount: Int = 0
        var graceActive: Int = 0
        var graceRemainingMs: Long = 0L
        var bottomOccluded: Int = 0
        var matchIou: Float = Float.NaN
        var matchCenterDx: Float = Float.NaN
        var matchCenterDy: Float = Float.NaN
        var switchReason: Int = 0
    }

    @Volatile
    private var started = false

    private val lock = Any()
    private val pool = ArrayDeque<TrackEvent>(prealloc)
    private val queue = ArrayDeque<TrackEvent>(prealloc)
    private val lineQueue = ArrayDeque<String>(prealloc)

    private var ht: HandlerThread? = null
    private var h: Handler? = null

    private val sb = StringBuilder(256)

    init {
        repeat(prealloc) { pool.addLast(TrackEvent()) }
    }

    fun start() {
        if (started) return
        started = true

        val thread = HandlerThread("mcaw_trace_log").also { it.start() }
        ht = thread
        h = Handler(thread.looper)
    }

    fun close() {
        started = false
        val handler = h
        handler?.post {
            drainOnce()
            runCatching { ht?.quitSafely() }
        }
        h = null
        ht = null
    }

    fun logTrack(
        tsMs: Long,
        lockedId: Long,
        lockedAgeFrames: Int,
        lockedMisses: Int,
        bestId: Long,
        bestScore: Float,
        lockedScore: Float,
        switchPending: Int,
        switchCount: Int,
        graceActive: Int,
        graceRemainingMs: Long,
        bottomOccluded: Int,
        matchIou: Float,
        matchCenterDx: Float,
        matchCenterDy: Float,
        switchReason: Int
    ) {
        if (!started) return

        val ev: TrackEvent = synchronized(lock) {
            if (pool.isEmpty()) return
            pool.removeFirst()
        }

        ev.tsMs = tsMs
        ev.lockedId = lockedId
        ev.lockedAgeFrames = lockedAgeFrames
        ev.lockedMisses = lockedMisses
        ev.bestId = bestId
        ev.bestScore = bestScore
        ev.lockedScore = lockedScore
        ev.switchPending = switchPending
        ev.switchCount = switchCount
        ev.graceActive = graceActive
        ev.graceRemainingMs = graceRemainingMs
        ev.bottomOccluded = bottomOccluded
        ev.matchIou = matchIou
        ev.matchCenterDx = matchCenterDx
        ev.matchCenterDy = matchCenterDy
        ev.switchReason = switchReason

        val shouldPostDrain: Boolean = synchronized(lock) {
            val wasEmpty = queue.isEmpty()
            queue.addLast(ev)
            wasEmpty
        }

        if (shouldPostDrain) {
            h?.post { drainOnce() }
        }
    }


    fun logLine(rawLine: String) {
        if (!started) return

        val shouldPostDrain: Boolean = synchronized(lock) {
            val wasEmpty = queue.isEmpty() && lineQueue.isEmpty()
            lineQueue.addLast(rawLine)
            wasEmpty
        }

        if (shouldPostDrain) {
            h?.post { drainOnce() }
        }
    }

    private fun drainOnce() {
        while (true) {
            val ev: TrackEvent? = synchronized(lock) {
                if (queue.isEmpty()) null else queue.removeFirst()
            }
            if (ev != null) {
                sb.setLength(0)
                TraceContract.appendTrackLine(
                    sb = sb,
                    tsMs = ev.tsMs,
                    lockedId = ev.lockedId,
                    lockedAgeFrames = ev.lockedAgeFrames,
                    lockedMisses = ev.lockedMisses,
                    bestId = ev.bestId,
                    bestScore = ev.bestScore,
                    lockedScore = ev.lockedScore,
                    switchPending = ev.switchPending,
                    switchCount = ev.switchCount,
                    graceActive = ev.graceActive,
                    graceRemainingMs = ev.graceRemainingMs,
                    bottomOccluded = ev.bottomOccluded,
                    matchIou = ev.matchIou,
                    matchCenterDx = ev.matchCenterDx,
                    matchCenterDy = ev.matchCenterDy,
                    switchReason = ev.switchReason
                )
                PublicLogWriter.appendLogLine(context, fileName, sb.toString().trimEnd())
                synchronized(lock) { pool.addLast(ev) }
                continue
            }

            val line: String? = synchronized(lock) {
                if (lineQueue.isEmpty()) null else lineQueue.removeFirst()
            }
            if (line != null) {
                PublicLogWriter.appendLogLine(context, fileName, line.trimEnd())
                continue
            }
            break
        }
    }
}

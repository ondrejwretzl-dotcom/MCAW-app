package com.mcaw.util

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import java.util.ArrayDeque

/**
 * Volitelný DEBUG trace logger.
 *
 * - zapisuje CSV mimo analyzer thread
 * - throttling je řízen volajícím (DetectionAnalyzer)
 * - bez alokací ve frame loop: object pool + fronta
 */
class SessionTraceLogger(
    private val context: Context,
    private val fileName: String,
    prealloc: Int = 64
) {
    private class TargetEvent {
        var tsMs: Long = 0L
        var kind: Int = 0
        var lockedId: Long = -1L
        var bestId: Long = -1L
        var bestPri: Float = 0f
        var lockedPri: Float = 0f
        var candId: Long = -1L
        var candCount: Int = 0
        var alertLevel: Int = 0
        var mode: Int = 0
    }

    @Volatile
    private var started = false

    private val lock = Any()
    private val pool = ArrayDeque<TargetEvent>(prealloc)
    private val queue = ArrayDeque<TargetEvent>(prealloc)

    private var ht: HandlerThread? = null
    private var h: Handler? = null

    private val sb = StringBuilder(128)

    init {
        repeat(prealloc) { pool.addLast(TargetEvent()) }
    }

    fun start() {
        if (started) return
        started = true

        val thread = HandlerThread("mcaw_trace_log").also { it.start() }
        ht = thread
        h = Handler(thread.looper)

        h?.post {
            PublicLogWriter.appendLogLine(context, fileName, TraceContract.HEADER.trimEnd())
        }
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

    fun logTarget(
        tsMs: Long,
        kind: Int,
        lockedId: Long,
        bestId: Long,
        bestPri: Float,
        lockedPri: Float,
        candId: Long,
        candCount: Int,
        alertLevel: Int,
        mode: Int
    ) {
        if (!started) return

        val ev: TargetEvent = synchronized(lock) {
            if (pool.isEmpty()) return
            pool.removeFirst()
        }

        ev.tsMs = tsMs
        ev.kind = kind
        ev.lockedId = lockedId
        ev.bestId = bestId
        ev.bestPri = bestPri
        ev.lockedPri = lockedPri
        ev.candId = candId
        ev.candCount = candCount
        ev.alertLevel = alertLevel
        ev.mode = mode

        val shouldPostDrain: Boolean = synchronized(lock) {
            val wasEmpty = queue.isEmpty()
            queue.addLast(ev)
            wasEmpty
        }

        if (shouldPostDrain) {
            h?.post { drainOnce() }
        }
    }

    private fun drainOnce() {
        while (true) {
            val ev: TargetEvent = synchronized(lock) {
                if (queue.isEmpty()) null else queue.removeFirst()
            } ?: break

            sb.setLength(0)
            sb.append('T').append(',')
            TraceContract.appendTargetLine(
                sb = sb,
                tsMs = ev.tsMs,
                kind = ev.kind,
                lockedId = ev.lockedId,
                bestId = ev.bestId,
                bestPri = ev.bestPri,
                lockedPri = ev.lockedPri,
                candId = ev.candId,
                candCount = ev.candCount,
                alertLevel = ev.alertLevel,
                mode = ev.mode
            )

            PublicLogWriter.appendLogLine(context, fileName, sb.toString().trimEnd())

            synchronized(lock) { pool.addLast(ev) }
        }
    }
}

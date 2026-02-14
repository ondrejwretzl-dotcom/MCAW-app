package com.mcaw.util

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import com.mcaw.risk.RiskEngine
import java.util.ArrayDeque
import kotlin.math.round

/**
 * ALWAYS-ON session event logger (CSV).
 *
 * Design goals (MCAW 2.0):
 * - no IO in analyzer loop
 * - no string formatting in analyzer loop
 * - sampled logging + level/state transitions
 * - object pool + queue to avoid GC spikes
 */
class SessionEventLogger(
    private val context: Context,
    private val fileName: String,
    prealloc: Int = 64
) {

    internal class Event {
        var tsMs: Long = 0L
        var risk: Float = 0f
        var level: Int = 0
        var state: RiskEngine.State = RiskEngine.State.SAFE
        var reasonBits: Int = 0
        var ttcSec: Float = Float.NaN
        var distM: Float = Float.NaN
        var relV: Float = Float.NaN
        var roi: Float = 0f
        var qualityPoor: Boolean = false
        var cutIn: Boolean = false
        var brake: Boolean = false
        var egoBrake: Float = 0f
        var mode: Int = 0
        var lockedId: Long = -1L
    }

    @Volatile
    private var started = false

    private val lock = Any()
    private val pool = ArrayDeque<Event>(prealloc)
    private val queue = ArrayDeque<Event>(prealloc)

    private var ht: HandlerThread? = null
    private var h: Handler? = null

    // Writer-thread reusable buffer
    private val sb = StringBuilder(256)

    init {
        repeat(prealloc) { pool.addLast(Event()) }
    }

    fun start() {
        if (started) return
        started = true

        val thread = HandlerThread("mcaw_event_log").also { it.start() }
        ht = thread
        h = Handler(thread.looper)

        h?.post {
            // One header per session file.
            PublicLogWriter.appendLogLine(context, fileName, LogContract.HEADER.trimEnd())
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

    /**
     * Enqueue one event. If pool empty => drop silently (prefer stability).
     */
    fun logEvent(
        tsMs: Long,
        risk: Float,
        level: Int,
        state: RiskEngine.State,
        reasonBits: Int,
        ttcSec: Float,
        distM: Float,
        relV: Float,
        roi: Float,
        qualityPoor: Boolean,
        cutIn: Boolean,
        brake: Boolean,
        egoBrake: Float,
        mode: Int,
        lockedId: Long
    ) {
        if (!started) return

        val ev: Event = synchronized(lock) {
            if (pool.isEmpty()) return
            pool.removeFirst()
        }

        ev.tsMs = tsMs
        ev.risk = risk
        ev.level = level
        ev.state = state
        ev.reasonBits = reasonBits
        ev.ttcSec = ttcSec
        ev.distM = distM
        ev.relV = relV
        ev.roi = roi
        ev.qualityPoor = qualityPoor
        ev.cutIn = cutIn
        ev.brake = brake
        ev.egoBrake = egoBrake
        ev.mode = mode
        ev.lockedId = lockedId

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
            val ev: Event = synchronized(lock) {
                if (queue.isEmpty()) null else queue.removeFirst()
            } ?: break

            // Format CSV line on writer thread.
            sb.setLength(0)
            sb.append(ev.tsMs).append(',')
                .append(float2(ev.risk)).append(',')
                .append(ev.level).append(',')
                .append(ev.state.name).append(',')
                .append(ev.reasonBits).append(',')
                .append(float2(ev.ttcSec)).append(',')
                .append(float2(ev.distM)).append(',')
                .append(float2(ev.relV)).append(',')
                .append(float2(ev.roi)).append(',')
                .append(if (ev.qualityPoor) 1 else 0).append(',')
                .append(if (ev.cutIn) 1 else 0).append(',')
                .append(if (ev.brake) 1 else 0).append(',')
                .append(float2(ev.egoBrake)).append(',')
                .append(ev.mode).append(',')
                .append(ev.lockedId)

            PublicLogWriter.appendLogLine(context, fileName, sb.toString())

            // Return to pool
            synchronized(lock) { pool.addLast(ev) }
        }
    }

    private fun float2(v: Float): String {
        // keep compact; avoid Locale issues by manual formatting
        return if (!v.isFinite()) "" else ((round(v * 100f) / 100f).toString())
    }

}

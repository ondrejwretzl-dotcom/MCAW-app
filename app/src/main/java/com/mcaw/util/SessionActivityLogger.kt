package com.mcaw.util

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import java.util.ArrayDeque

/**
 * Lightweight session "activity" logger for sparse UI/service events.
 *
 * Writes to the unified session CSV file as:
 *   S,<ts_ms>,"message"
 *
 * Goals:
 * - no IO on UI thread
 * - no blocking of critical paths
 * - reuse writer thread + small pool to avoid GC
 */
object SessionActivityLogger {

    private class Line {
        var tsMs: Long = 0L
        var msg: String = ""
        fun reset() { tsMs = 0L; msg = "" }
    }

    @Volatile private var inited = false
    @Volatile private var enabled = true

    private val lock = Any()
    private val pool = ArrayDeque<Line>(32)
    private val queue = ArrayDeque<Line>(32)

    private var ctx: Context? = null
    private var fileName: String = ""
    private var ht: HandlerThread? = null
    private var h: Handler? = null

    fun init(context: Context, sessionFileName: String) {
        if (inited) return
        synchronized(this) {
            if (inited) return
            ctx = context.applicationContext
            fileName = sessionFileName
            repeat(32) { pool.addLast(Line()) }
            val thread = HandlerThread("mcaw_activity_log").also { it.start() }
            ht = thread
            h = Handler(thread.looper)
            inited = true
        }
    }

    fun setEnabled(on: Boolean) { enabled = on }

    fun log(message: String) {
        if (!enabled || !inited) return
        val clean = message.replace("\n", " ").replace("\r", " ").trim()
        if (clean.isEmpty()) return

        val line: Line = synchronized(lock) {
            if (pool.isEmpty()) return
            pool.removeFirst()
        }
        line.tsMs = System.currentTimeMillis()
        line.msg = clean

        val shouldPost: Boolean = synchronized(lock) {
            val wasEmpty = queue.isEmpty()
            queue.addLast(line)
            wasEmpty
        }
        if (shouldPost) {
            h?.post { drainOnce() }
        }
    }

    fun close() {
        enabled = false
        val handler = h
        handler?.post {
            drainOnce()
            runCatching { ht?.quitSafely() }
        }
        h = null
        ht = null
    }

    private fun drainOnce() {
        val context = ctx ?: return
        while (true) {
            val line: Line = synchronized(lock) {
                if (queue.isEmpty()) null else queue.removeFirst()
            } ?: break

            val escaped = "\"" + line.msg.replace("\"", "\"\"") + "\""
            PublicLogWriter.appendLogLine(context, fileName, "S,${line.tsMs},$escaped")

            line.reset()
            synchronized(lock) { pool.addLast(line) }
        }
    }
}

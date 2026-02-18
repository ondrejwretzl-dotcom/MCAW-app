package com.mcaw.risk

import org.junit.rules.TestWatcher
import org.junit.runner.Description
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Lightweight test report collector.
 *
 * Goal:
 * - Produce a human-readable summary even when all tests PASS.
 * - Keep it simple and deterministic.
 *
 * Note: This is test-only code. IO here is OK.
 */
internal object RiskTestReport {

    data class Entry(
        val testName: String,
        var status: String,
        var note: String = ""
    )

    private val entries = mutableListOf<Entry>()

    fun watcher(): TestWatcher = object : TestWatcher() {
        private var current: Entry? = null

        override fun starting(description: Description) {
            val e = Entry(testName = description.methodName ?: description.displayName, status = "RUN")
            synchronized(entries) { entries.add(e) }
            current = e
        }

        override fun succeeded(description: Description) {
            current?.status = "PASS"
        }

        override fun failed(e: Throwable, description: Description) {
            current?.status = "FAIL"
            current?.note = e.message ?: e.javaClass.simpleName
        }
    }

    fun addNote(note: String) {
        synchronized(entries) {
            entries.lastOrNull()?.let { last ->
                last.note = if (last.note.isBlank()) note else (last.note + " | " + note)
            }
        }
    }

    fun writeReportIfAny(outputDir: File = File("build/reports")) {
        val snapshot = synchronized(entries) { entries.toList() }
        if (snapshot.isEmpty()) return

        outputDir.mkdirs()
        val ts = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val out = File(outputDir, "mcaw_risk_tests_" + ts + ".txt")

        val sb = StringBuilder(8_192)
        sb.append("MCAW 2.0 – RiskEngine modelové testy (offline)\n")
        sb.append("Generated: ").append(ts).append("\n\n")

        val pass = snapshot.count { it.status == "PASS" }
        val fail = snapshot.count { it.status == "FAIL" }
        val run = snapshot.size
        sb.append("Summary: ").append(pass).append(" PASS / ").append(fail).append(" FAIL (total ").append(run).append(")\n\n")

        for (e in snapshot) {
            sb.append("- [").append(e.status).append("] ").append(e.testName)
            if (e.note.isNotBlank()) sb.append(" — ").append(e.note)
            sb.append('\n')
        }

        out.writeText(sb.toString())
        // Also print to stdout so CI logs contain it.
        println(sb.toString())
        println("[RiskTestReport] Written: " + out.absolutePath)
    }
}

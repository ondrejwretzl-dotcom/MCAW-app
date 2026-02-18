package com.mcaw.risk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.AfterClass
import org.junit.Rule
import org.junit.Test

/**
 * D1 – Modelové scénáře pro RiskEngine (offline).
 * Cíl: rychle chytat regresní chování (blikání, pozdní/žádné RED, falešné RED).
 */
class RiskEngineScenarioTest {

    @get:Rule
    val report = RiskTestReport.watcher()

    private data class Frame(
        val tsMs: Long,
        val distM: Float,
        val relMps: Float,
        val ttcSec: Float,
        val ttcSlope: Float,
        val brakeCue: Float = 0f,
        val cutIn: Boolean = false,
        val qW: Float = 1.0f
    )

    private fun runScenario(frames: List<Frame>, mode: Int = 1): List<Int> {
        val engine = RiskEngine()
        val levels = ArrayList<Int>(frames.size)
        for (f in frames) {
            val r = engine.evaluate(
                tsMs = f.tsMs,
                effectiveMode = mode,
                distanceM = f.distM,
                approachSpeedMps = f.relMps.coerceAtLeast(0f),
                ttcSec = f.ttcSec,
                ttcSlopeSecPerSec = f.ttcSlope,
                roiContainment = 1f,
                egoOffsetN = 0f,
                cutInActive = f.cutIn,
                brakeCueActive = f.brakeCue > 0f,
                brakeCueStrength = f.brakeCue.coerceIn(0f, 1f),
                qualityWeight = f.qW,
                riderSpeedMps = 10f,
                egoBrakingConfidence = if (f.brakeCue > 0f) 0.8f else 0.0f,
                leanDeg = Float.NaN
            )
            levels.add(r.level)
        }
        return levels
    }

    private fun transitions(levels: List<Int>): Int {
        var t = 0
        for (i in 1 until levels.size) if (levels[i] != levels[i - 1]) t++
        return t
    }

    @Test
    fun scenario1_unexpectedJam_shouldReachRed() {
        // Dojíždím do kolony, nebrzdím -> TTC padá rychle, rel vysoká.
        val frames = (0 until 35).map { i ->
            val ts = i * 50L
            val ttc = (6.0f - i * 0.16f).coerceAtLeast(0.8f)
            val dist = (70f - i * 1.8f).coerceAtLeast(9f)
            Frame(ts, distM = dist, relMps = 10.0f, ttcSec = ttc, ttcSlope = -1.6f)
        }
        val levels = runScenario(frames)
        RiskTestReport.addNote("Expect RED reachable: fast closing / TTC trending down")
        assertTrue("Očekávám, že se objeví RED (level=2)", levels.any { it == 2 })
    }

    @Test
    fun scenario2_knownJamBraking_shouldNotGoRed() {
        // Řidič ví o koloně, brzdí -> rel rychle klesá, slope se zlepšuje.
        val frames = (0 until 45).map { i ->
            val ts = i * 50L
            val rel = (8.0f - i * 0.20f).coerceAtLeast(0.0f)
            val ttc = (5.0f - i * 0.06f).coerceAtLeast(1.6f)
            val dist = (55f - i * 1.0f).coerceAtLeast(10f)
            val brake = (0.2f + i * 0.02f).coerceIn(0f, 1f)
            val slope = if (i < 10) -0.9f else -0.2f
            Frame(ts, distM = dist, relMps = rel, ttcSec = ttc, ttcSlope = slope, brakeCue = brake)
        }
        val levels = runScenario(frames)
        RiskTestReport.addNote("Controlled braking: never RED")
        assertTrue("Nemělo by padnout RED při kontrolovaném brzdění", levels.none { it == 2 })
        assertTrue("ORANGE se může objevit", levels.any { it == 1 } || levels.any { it == 0 })
    }

    @Test
    fun scenario3_inTrafficJam_shouldBeStableNoRed() {
        // Jedu v koloně: dist malá, rel ~0, slope ~0 -> žádné RED, minimum přechodů.
        val frames = (0 until 120).map { i ->
            val ts = i * 50L
            val dist = 7.5f + (if (i % 30 < 15) 0.3f else -0.3f)
            Frame(ts, distM = dist, relMps = 0.2f, ttcSec = 10f, ttcSlope = 0f)
        }
        val levels = runScenario(frames)
        RiskTestReport.addNote("Jam steady-state: low transitions")
        assertTrue("V koloně nesmí být RED", levels.none { it == 2 })
        assertTrue("Anti-blink: nechci nadměrné přechody", transitions(levels) <= 6)
    }

    @Test
    fun scenario4_highwayTruckFastClosing_shouldReachRed() {
        // Dálnice: rychlé dojíždění kamionu.
        val frames = (0 until 25).map { i ->
            val ts = i * 50L
            val ttc = (4.0f - i * 0.14f).coerceAtLeast(0.7f)
            val dist = (80f - i * 3.2f).coerceAtLeast(10f)
            Frame(ts, distM = dist, relMps = 16.0f, ttcSec = ttc, ttcSlope = -2.0f)
        }
        val levels = runScenario(frames)
        RiskTestReport.addNote("Highway fast closing: RED must happen")
        assertTrue("Na dálnici při rychlém dojíždění očekávám RED", levels.any { it == 2 })
    }

    @Test
    fun scenario5_ttcGlitchSpike_shouldNotGoRed() {
        // Glitch: 1 frame TTC spadne, ale dist/rel/slope to nepotvrdí -> nesmí RED.
        val frames = buildList {
            // stabilně safe
            for (i in 0 until 20) add(Frame(i * 50L, distM = 40f, relMps = 0.5f, ttcSec = 20f, ttcSlope = 0f))
            // glitch frame
            add(Frame(20 * 50L, distM = 40f, relMps = 0.5f, ttcSec = 0.6f, ttcSlope = 0f))
            // návrat
            for (i in 21 until 45) add(Frame(i * 50L, distM = 40f, relMps = 0.5f, ttcSec = 20f, ttcSlope = 0f))
        }
        val levels = runScenario(frames)
        RiskTestReport.addNote("Anti-glitch: 1-frame TTC spike must not RED")
        assertTrue("TTC spike bez potvrzení nesmí vyvolat RED", levels.none { it == 2 })

        // sanity: maximálně krátké vybočení (ORANGE může na chvilku nastat, ale nemá blikat)
        assertTrue("Nechci cvakání – omezený počet přechodů", transitions(levels) <= 4)
    }

    @Test
    fun scenario_qualityLow_shouldBeMoreConservative() {
        // Stejné vstupy, jen různé qualityWeight -> při nízké kvalitě se má snižovat agrese.
        val base = (0 until 30).map { i ->
            val ts = i * 50L
            val ttc = (3.2f - i * 0.06f).coerceAtLeast(1.3f)
            val dist = (25f - i * 0.5f).coerceAtLeast(9f)
            Frame(ts, distM = dist, relMps = 5.0f, ttcSec = ttc, ttcSlope = -0.7f)
        }
        val goodQ = runScenario(base.map { it.copy(qW = 1.0f) })
        val badQ = runScenario(base.map { it.copy(qW = 0.65f) })

        val maxGood = goodQ.maxOrNull() ?: 0
        val maxBad = badQ.maxOrNull() ?: 0

        RiskTestReport.addNote("Quality weight: lowQ should not be more aggressive")

        assertTrue("Nízká kvalita má být konzervativnější (max level nesmí být vyšší)", maxBad <= maxGood)

        // pokud goodQ dosáhne RED, badQ má typicky skončit níž nebo později
        if (maxGood == 2) {
            val firstGoodRed = goodQ.indexOfFirst { it == 2 }
            val firstBadRed = badQ.indexOfFirst { it == 2 }
            assertTrue(
                "Při nízké kvalitě očekávám pozdější nebo žádný RED",
                firstBadRed == -1 || firstBadRed >= firstGoodRed
            )
        }
    }

    @Test
    fun reasonBits_redMustBeAuditable_andVersioned() {
        val engine = RiskEngine()
        val r = engine.evaluate(
            tsMs = 0L,
            effectiveMode = 1,
            distanceM = 8.0f,
            approachSpeedMps = 10.0f,
            ttcSec = 0.9f,
            ttcSlopeSecPerSec = -2.0f,
            roiContainment = 1f,
            egoOffsetN = 0f,
            cutInActive = false,
            brakeCueActive = false,
            brakeCueStrength = 0f,
            qualityWeight = 1.0f,
            riderSpeedMps = 10f,
            egoBrakingConfidence = 0f,
            leanDeg = Float.NaN
        )

        assertEquals("Sanity: očekávám RED", 2, r.level)

        RiskTestReport.addNote("Audit: RED must include version + core bits + RED_OK")

        // version is embedded in the top nibble
        assertEquals(2, RiskEngine.reasonVersion(r.reasonBits))

        val payload = RiskEngine.stripReasonVersion(r.reasonBits)
        assertTrue((payload and RiskEngine.BIT_TTC) != 0)
        assertTrue((payload and RiskEngine.BIT_DIST) != 0)
        assertTrue((payload and RiskEngine.BIT_REL) != 0)
        assertTrue((payload and RiskEngine.BIT_TTC_SLOPE_STRONG) != 0)
        assertTrue((payload and RiskEngine.BIT_RED_COMBO_OK) != 0)
    }

    @Test
    fun reasonBits_redGuarded_shouldBeAuditable() {
        // Připravím situaci, kde risk může krátce přelézt do "RED" podle riskScore,
        // ale combo guard ho má potlačit (chybí strongDist/strongRel i slopeStrong).
        val engine = RiskEngine()
        val r = engine.evaluate(
            tsMs = 0L,
            effectiveMode = 1,
            distanceM = 10.0f,          // distScore ~0.84 (těsně pod strongK ~0.85)
            approachSpeedMps = 0.5f,    // rel nízká
            ttcSec = 0.8f,              // strong TTC
            ttcSlopeSecPerSec = -0.2f,  // slopeStrong = false
            roiContainment = 1f,
            egoOffsetN = 0f,
            cutInActive = false,
            brakeCueActive = true,
            brakeCueStrength = 1.0f,
            qualityWeight = 1.0f,
            riderSpeedMps = 10f,
            egoBrakingConfidence = 0.8f,
            leanDeg = Float.NaN
        )

        assertEquals("Očekávám ORANGE (RED má být potlačen guardem)", 1, r.level)

        RiskTestReport.addNote("Audit: guarded RED must set RED_GUARDED")
        assertEquals(2, RiskEngine.reasonVersion(r.reasonBits))

        val payload = RiskEngine.stripReasonVersion(r.reasonBits)
        assertTrue("Musí být označeno, že guard potlačil RED", (payload and RiskEngine.BIT_RED_GUARDED) != 0)
        assertTrue("Nesmí být označeno RED_OK, když RED neprošel", (payload and RiskEngine.BIT_RED_COMBO_OK) == 0)
    }

    companion object {
        @JvmStatic
        @AfterClass
        fun writeHumanReadableReport() {
            // Always generate a readable report, even when everything passes.
            RiskTestReport.writeReportIfAny()
        }
    }
}

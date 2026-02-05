package com.mcaw.ai

import com.mcaw.model.Box
import kotlin.math.ln

object DetectionPhysics {

    /**
     * Hrubý monokulární odhad vzdálenosti dle výšky boxu v pixelech.
     * Vyžaduje odhad fokální délky v pixelech (focalPx) a reálnou výšku objektu (m).
     */
   fun estimateDistanceMeters(
        bbox: Box,
        frameHeightPx: Int,
        focalPx: Float,
        realHeightM: Float = 1.5f, // detekovana vyska, treba motorka, auto
        // tuning knobs:
        minBoxHeightPx: Float = 18f,     // pod to je výpočet extrémně šumový
        minDistanceM: Float = 0.7f,
        maxDistanceM: Float = 150f
    ): Float? {
        if (frameHeightPx <= 0 || focalPx <= 0f || realHeightM <= 0f) return null

        // bbox.h může být buď v pixelech, nebo v normalizovaných 0..1
        val hRaw = bbox.h
        if (hRaw <= 0f) return null

        val hPx = when {
            // typicky normalizované hodnoty (0..1), někdy do ~2 kvůli chybám/rotaci
            hRaw <= 2.0f -> hRaw * frameHeightPx.toFloat()
            else -> hRaw
        }

        if (hPx < minBoxHeightPx) return null

        // Z ~ f * H / h
        val dist = (focalPx * realHeightM / hPx)
        if (!dist.isFinite()) return null

        return dist.coerceIn(minDistanceM, maxDistanceM)
    }


    /**
     * TTC z růstu velikosti bboxu (logaritmická derivace).
     * prevH, currH v pixelech; dtSec v sekundách.
     * TTC ? -1 / (d(ln h)/dt) = - dt / ln(currH/prevH)
     */
    fun computeTtcFromHeights(
        prevH: Float,
        currH: Float,
        dtSec: Float,
        // tuning knobs:
        minDtSec: Float = 0.06f,          // menší dt je šum (záleží na FPS)
        minGrowthRatio: Float = 1.03f,    // musí se zvětšit aspoň o ~3 %
        minDeltaHPx: Float = 2.0f,        // alternativní ochrana proti jitteru (platí pro px i normalizované „spíš míň“)
        maxTtcSec: Float = 20f
    ): Float? {
        if (prevH <= 0f || currH <= 0f) return null
        if (dtSec <= minDtSec) return null

        val ratio = currH / prevH
        if (!ratio.isFinite() || ratio <= 0f) return null

        // jitter guard – musí to reálně růst
        if (ratio < minGrowthRatio) return null
        if ((currH - prevH) < minDeltaHPx) return null

        val dln = ln(ratio)
        // při přibližování je dln > 0; když <= 0, nepřibližuje se
        if (dln <= 0f) return null

        val ttc = dtSec / dln
        if (!ttc.isFinite() || ttc <= 0f) return null

        return ttc.coerceAtMost(maxTtcSec)
    }


    /**
     * Jednoduchý adaptivní práh pro TTC (čím větší relativní rychlost, tím přísnější práh).
     */
    fun adaptiveTtcThreshold(relSpeedMps: Float?): Float {
        val v = (relSpeedMps ?: 0f).coerceAtLeast(0f)
        return when {
            v >= 25f -> 1.0f   // ~90 km/h
            v >= 15f -> 1.3f   // ~54 km/h
            v >=  8f -> 1.6f   // ~29 km/h
            else     -> 2.0f
        }
    }
}

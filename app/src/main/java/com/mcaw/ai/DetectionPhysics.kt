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
 * Odhad vzdálenosti pomocí ground-plane (kamera výška + pitch).
 * Používá spodní hranu bboxu jako přibližný kontakt se zemí.
 *
 * Model:
 *   angleToPoint = atan((yBottom - cy) / focalPx)
 *   total = pitchDown + angleToPoint
 *   dist = camHeight / tan(total)
 */
fun estimateDistanceGroundPlaneMeters(
    bbox: Box,
    frameHeightPx: Int,
    focalPx: Float,
    camHeightM: Float,
    pitchDownDeg: Float,
    minDistanceM: Float = 0.7f,
    maxDistanceM: Float = 200f
): Float? {
    if (frameHeightPx <= 0 || focalPx <= 0f) return null
    if (!camHeightM.isFinite() || camHeightM <= 0.1f) return null

    val y2Raw = bbox.y2
    if (!y2Raw.isFinite() || y2Raw <= 0f) return null

    val yBottomPx = when {
        y2Raw <= 2.0f -> y2Raw * frameHeightPx.toFloat()
        else -> y2Raw
    }.coerceIn(0f, frameHeightPx.toFloat())

    val cy = frameHeightPx.toFloat() * 0.5f
    val pitchRad = Math.toRadians(pitchDownDeg.toDouble()).toFloat()
    val angleToPoint = kotlin.math.atan((yBottomPx - cy) / focalPx)
    val total = pitchRad + angleToPoint

    // total <= 0 => bod je nad horizontem; dist jde do nekonečna / záporná
    if (!total.isFinite() || total <= 0.001f) return null

    val dist = (camHeightM / kotlin.math.tan(total))
    if (!dist.isFinite() || dist <= 0f) return null
    return dist.coerceIn(minDistanceM, maxDistanceM)
}

/**
 * Ground-plane distance for an arbitrary image Y position (pixel coordinates).
 * Useful for estimating the distance to the bottom edge of ROI ("minimum observable distance").
 */
fun estimateDistanceGroundPlaneMetersAtYPx(
    yBottomPx: Float,
    frameHeightPx: Int,
    focalPx: Float,
    camHeightM: Float,
    pitchDownDeg: Float,
    minDistanceM: Float = 0.7f,
    maxDistanceM: Float = 200f
): Float? {
    if (frameHeightPx <= 0 || focalPx <= 0f) return null
    if (!camHeightM.isFinite() || camHeightM <= 0.1f) return null

    val y = yBottomPx.coerceIn(0f, frameHeightPx.toFloat())
    val cy = frameHeightPx.toFloat() * 0.5f
    val pitchRad = Math.toRadians(pitchDownDeg.toDouble()).toFloat()
    val angleToPoint = kotlin.math.atan((y - cy) / focalPx)
    val total = pitchRad + angleToPoint

    if (!total.isFinite() || total <= 0.001f) return null
    val dist = (camHeightM / kotlin.math.tan(total))
    if (!dist.isFinite() || dist <= 0f) return null
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

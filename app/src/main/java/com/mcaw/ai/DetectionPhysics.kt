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
        realHeightM: Float = 1.5f // typická výška auta/motorky (lze pozdìji pøepsat dle labelu)
    ): Float? {
        val h = bbox.h
        if (h <= 1f || focalPx <= 0f) return null
        // Z ~ f * H / h
        return (focalPx * realHeightM / h).coerceAtLeast(0f)
    }

    /**
     * TTC z rùstu velikosti bboxu (logaritmická derivace).
     * prevH, currH v pixelech; dtSec v sekundách.
     * TTC ? -1 / (d(ln h)/dt) = - dt / ln(currH/prevH)
     */
    fun computeTtcFromHeights(prevH: Float, currH: Float, dtSec: Float): Float? {
        if (prevH <= 1f || currH <= 1f || dtSec <= 0f) return null
        val ratio = currH / prevH
        if (ratio <= 0f || ratio == 1f) return null
        val dln = ln(ratio)
        if (dln >= 0f) return null // nezvìtšuje se => nepøibližuje se
        return (-dtSec / dln).takeIf { it.isFinite() && it > 0f }
    }

    /**
     * Jednoduchý adaptivní práh pro TTC (èím vìtší relativní rychlost, tím pøísnìjší práh).
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

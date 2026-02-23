/*
 * MCAW RiskEngine reference port (TypeScript)
 * Goal: bit-for-bit compatible with app/src/main/java/com/mcaw/risk/RiskEngine.kt (v2 reasonBits)
 *
 * Notes:
 * - No allocations in Kotlin hot path is irrelevant here; this is tooling.
 * - Keep numeric behavior close: use number (double) and clamp like Kotlin.
 */

export type State = 'SAFE' | 'CAUTION' | 'CRITICAL';

export interface Thresholds {
  ttcOrange: number;
  ttcRed: number;
  distOrange: number;
  distRed: number;
  relOrange: number;
  relRed: number;
}

export interface DerivedThresholds extends Thresholds {
  mode: number;
  qualityWeight: number;
  conserv: number;
  orangeOn: number;
  orangeOff: number;
  redOn: number;
  redOff: number;
  slopeThr: number;
  strongK: number;
  midK: number;
  distDynamic: boolean;
  distFromSpeed: boolean;
  distHeadwayOrangeSec: number;
  distHeadwayRedSec: number;
}

export interface EvaluateOptions {
  dynamicDistanceEnabled?: boolean;
  dynamicDistanceRedSec?: number;
  dynamicDistanceOrangeSec?: number;
}

export interface EvaluateInput {
  tsMs: number;
  effectiveMode: number;
  distanceM: number;
  approachSpeedMps: number;
  ttcSec: number;
  ttcSlopeSecPerSec?: number;
  roiContainment: number;
  egoOffsetN: number;
  cutInActive: boolean;
  brakeCueActive: boolean;
  brakeCueStrength: number;
  occlusionCloseFactor?: number;
  occlusionCloseEligible?: boolean;
  qualityWeight?: number;
  riderSpeedMps: number;
  riderSpeedConfidence: number;
  egoBrakingConfidence: number;
  leanDeg: number; // NaN allowed
}

export interface EvaluateOutput {
  level: number; // 0..2
  riskScore: number; // EMA risk 0..1
  reasonBits: number;
  state: State;

  // tooling extras (not in Kotlin Result)
  rawRisk: number;
  allowRed: boolean;
  preGuardLevel: number;
}

// --- Reason bits contract (match Kotlin) ---
const REASON_BITS_VERSION_CURRENT = 2;
const REASON_BITS_VERSION_SHIFT = 28;
const REASON_BITS_VERSION_MASK = (0xF << REASON_BITS_VERSION_SHIFT) >>> 0;
const REASON_BITS_PAYLOAD_MASK = (~REASON_BITS_VERSION_MASK) >>> 0;

export const BIT_TTC = 1 << 0;
export const BIT_DIST = 1 << 1;
export const BIT_REL = 1 << 2;
export const BIT_ROI_LOW = 1 << 3;
export const BIT_BRAKE_CUE = 1 << 4;
export const BIT_CUT_IN = 1 << 5;
export const BIT_EGO_BRAKE = 1 << 6;
export const BIT_QUALITY_CONSERV = 1 << 7;
export const BIT_RIDER_STAND = 1 << 8;
export const BIT_TTC_SLOPE_STRONG = 1 << 9;
export const BIT_RED_COMBO_OK = 1 << 10;
export const BIT_RED_GUARDED = 1 << 11;
export const BIT_SPEED_LOWCONF = 1 << 12;
export const BIT_BOTTOM_OCCLUDED_CLOSE = 1 << 13;
export const BIT_DIST_DYNAMIC = 1 << 14;
export const BIT_DIST_FIXED_FALLBACK = 1 << 15;

export function stripReasonVersion(reasonBits: number): number {
  return (reasonBits >>> 0) & REASON_BITS_PAYLOAD_MASK;
}

export function packReasonBits(payloadBits: number, version: number = REASON_BITS_VERSION_CURRENT): number {
  if ((payloadBits | 0) === 0) return 0;
  const v = (version & 0xF) << REASON_BITS_VERSION_SHIFT;
  return (((payloadBits >>> 0) & REASON_BITS_PAYLOAD_MASK) | (v >>> 0)) >>> 0;
}

function clamp(x: number, lo: number, hi: number): number {
  return Math.min(hi, Math.max(lo, x));
}

function isFiniteNumber(x: number): boolean {
  return Number.isFinite(x);
}

export class RiskEngineRef {
  // hysteresis state
  private lastLevel = 0;
  private bottomOccludedRedHoldUntilMs = 0;

  // EMA state
  private emaRisk = 0;
  private emaInit = false;

  // TTC hysteresis state
  private lastTtcLevel = 0;

  resetState(): void {
    this.lastLevel = 0;
    this.emaRisk = 0;
    this.emaInit = false;
    this.lastTtcLevel = 0;
    this.bottomOccludedRedHoldUntilMs = 0;
  }

  debugDerivedThresholds(
    effectiveMode: number,
    qualityWeight: number,
    thresholdsOverride?: Thresholds,
    options?: EvaluateOptions
  ): DerivedThresholds {
    const thr = thresholdsOverride ?? this.thresholdsForMode(effectiveMode);
    const dynEnabled = options?.dynamicDistanceEnabled ?? true;
    const dynRedSec = options?.dynamicDistanceRedSec ?? 1.2;
    const dynOrangeSec = Math.max(dynRedSec + 0.2, options?.dynamicDistanceOrangeSec ?? 1.8);
    const qW = clamp(qualityWeight, 0.60, 1.0);
    const conserv = clamp(1.0 - qW, 0.0, 1.0);

    const orangeOn = 0.45 + 0.17 * conserv;
    const redOn = 0.75 + 0.07 * conserv;
    const orangeOff = orangeOn - 0.06;
    const redOff = redOn - 0.05;

    const slopeThr = -1.0 - 0.40 * conserv;
    const strongK = 0.85 + 0.05 * conserv;
    const midK = 0.60 + 0.10 * conserv;

    return {
      mode: effectiveMode,
      qualityWeight: qW,
      conserv,
      ...thr,
      orangeOn,
      orangeOff,
      redOn,
      redOff,
      slopeThr,
      strongK,
      midK,
      distDynamic: dynEnabled,
      distFromSpeed: false,
      distHeadwayOrangeSec: dynOrangeSec,
      distHeadwayRedSec: dynRedSec,
    };
  }

  evaluate(input: EvaluateInput, thresholdsOverride?: Thresholds, options?: EvaluateOptions): EvaluateOutput {
    const thr = thresholdsOverride ?? this.thresholdsForMode(input.effectiveMode);

    const ttcSlope = isFiniteNumber(input.ttcSlopeSecPerSec ?? 0) ? (input.ttcSlopeSecPerSec ?? 0) : 0;
    const occF = clamp(input.occlusionCloseFactor ?? 0, 0, 1);
    const occlusionEligible = input.occlusionCloseEligible ?? false;
    const riderSpeedMps = input.riderSpeedMps ?? 0;
    const riderSpeedConfidence = input.riderSpeedConfidence ?? 1;
    const roiContainment = input.roiContainment ?? 1;
    const egoOffsetN = input.egoOffsetN ?? 0;
    const brakeCueStrength = input.brakeCueStrength ?? 0;

    const dynDistEnabled = options?.dynamicDistanceEnabled ?? true;
    const dynRedSec = options?.dynamicDistanceRedSec ?? 1.2;
    const dynOrangeSec = Math.max(dynRedSec + 0.2, options?.dynamicDistanceOrangeSec ?? 1.8);
    const speedForDynDistOk = isFiniteNumber(riderSpeedMps) && riderSpeedMps >= 0 && riderSpeedConfidence >= 0.20;

    const distRedThr = (dynDistEnabled && speedForDynDistOk)
      ? clamp(riderSpeedMps * dynRedSec, 3.0, 220.0)
      : thr.distRed;
    const distOrangeThr = (dynDistEnabled && speedForDynDistOk)
      ? clamp(riderSpeedMps * dynOrangeSec, distRedThr + 1.5, 260.0)
      : thr.distOrange;

    // --- Core scores ---
    const ttcLevel = this.ttcLevelWithHysteresis(input.ttcSec, thr.ttcOrange, thr.ttcRed);
    let ttcScore: number;
    if (ttcLevel === 2) ttcScore = 1.0;
    else if (ttcLevel === 1) ttcScore = 0.70;
    else {
      if (!isFiniteNumber(input.ttcSec) || input.ttcSec <= 0) ttcScore = 0;
      else {
        const t = clamp(Math.min(input.ttcSec, 10) / 10, 0, 1);
        ttcScore = clamp(0.35 * (1.0 - t), 0, 0.35);
      }
    }

    const distScore = this.scoreLowIsBad(input.distanceM, distRedThr, distOrangeThr);
    const relScore = this.scoreHighIsBad(input.approachSpeedMps, thr.relOrange, thr.relRed);

    const roiC = clamp(roiContainment, 0, 1);
    const off = clamp(egoOffsetN, 0, 2);
    const egoScore = clamp(1.0 - (off / 1.15), 0, 1);
    const roiScore = clamp(roiC * 0.70 + egoScore * 0.30, 0, 1);

    const brakeStrength = clamp(brakeCueStrength, 0, 1);
    const brakeScore = input.brakeCueActive ? (0.70 + 0.30 * brakeStrength) : 0;
    const cutInScore = input.cutInActive ? 1.0 : 0;
    const occlusionBoost = occlusionEligible ? (0.42 * occF) : 0;

    const egoBrake = clamp(input.egoBrakingConfidence, 0, 1);

    // --- Weighted raw risk ---
    let rawRisk =
      (ttcScore * 0.35) +
      (distScore * 0.20) +
      (relScore * 0.20) +
      (roiScore * 0.10) +
      (brakeScore * 0.10) +
      (cutInScore * 0.05) +
      occlusionBoost;

    if (egoBrake >= 0.65) {
      rawRisk += 0.08 * clamp((egoBrake - 0.65) / 0.35, 0, 1);
    }

    const qW = clamp(input.qualityWeight ?? 1.0, 0.60, 1.0);
    const conserv = clamp(1.0 - qW, 0, 1);
    rawRisk *= (1.0 - 0.12 * conserv);

    const leanDeg = input.leanDeg;
    if (isFiniteNumber(leanDeg)) {
      const lean = Math.abs(leanDeg);
      const k = clamp((lean - 20.0) / 25.0, 0, 1);
      rawRisk *= (1.0 - 0.12 * k);
    }

    rawRisk = clamp(rawRisk, 0, 1);

    // --- EMA integrate ---
    const riseAlpha = 0.30;
    const fallAlpha = 0.15;
    if (!this.emaInit) {
      this.emaRisk = rawRisk;
      this.emaInit = true;
    } else {
      const a = rawRisk >= this.emaRisk ? riseAlpha : fallAlpha;
      this.emaRisk += a * (rawRisk - this.emaRisk);
    }
    const risk = clamp(this.emaRisk, 0, 1);

    // --- CRITICAL combo guard ---
    const slopeThr = -1.0 - 0.40 * conserv;
    const slopeStrong = ttcSlope <= slopeThr;
    const strongTtc = (ttcLevel >= 2) || (ttcScore >= 0.85);
    const strongK = 0.85 + 0.05 * conserv;
    const midK = 0.60 + 0.10 * conserv;
    const strongDist = distScore >= strongK;
    const strongRel = relScore >= strongK;
    const midDist = distScore >= midK;
    const midRel = relScore >= midK;
    const allowRed = strongTtc && (strongDist || strongRel || (slopeStrong && (midDist || midRel)));

    const preGuardLevel = this.riskToLevelWithHysteresis(risk, conserv);
    let level = preGuardLevel;

    const occlusionRed = occlusionEligible && occF >= 0.90;
    if (occlusionRed) {
      level = 2;
      this.bottomOccludedRedHoldUntilMs = input.tsMs + 500;
    } else if (level < 2 && this.bottomOccludedRedHoldUntilMs > input.tsMs) {
      level = 2;
    }

    if (preGuardLevel === 2 && !allowRed && !occlusionRed) {
      this.lastLevel = 1;
      level = 1;
    }

    const state: State = level === 2 ? 'CRITICAL' : (level === 1 ? 'CAUTION' : 'SAFE');

    // --- reason bits ---
    let bits = 0;
    if (level > 0) {
      if (ttcLevel > 0 || ttcScore >= 0.55) bits |= BIT_TTC;
      if (distScore >= 0.55) bits |= BIT_DIST;
      if (relScore >= 0.60) bits |= BIT_REL;
      if (roiScore <= 0.40) bits |= BIT_ROI_LOW;
      if (input.brakeCueActive) bits |= BIT_BRAKE_CUE;
      if (input.cutInActive) bits |= BIT_CUT_IN;
      if (egoBrake >= 0.65) bits |= BIT_EGO_BRAKE;
      if (conserv >= 0.15) bits |= BIT_QUALITY_CONSERV;
      if (riderSpeedConfidence < 0.60) bits |= BIT_SPEED_LOWCONF;
      if (dynDistEnabled && speedForDynDistOk) bits |= BIT_DIST_DYNAMIC;
      else bits |= BIT_DIST_FIXED_FALLBACK;
      if (occlusionRed || (occlusionEligible && occF > 0.5)) bits |= BIT_BOTTOM_OCCLUDED_CLOSE;
      if (slopeStrong) bits |= BIT_TTC_SLOPE_STRONG;
      if (level === 2 && allowRed) bits |= BIT_RED_COMBO_OK;
      if (level === 1 && preGuardLevel === 2 && !allowRed) bits |= BIT_RED_GUARDED;
    } else {
      if (conserv >= 0.15) bits |= BIT_QUALITY_CONSERV;
      if (riderSpeedConfidence < 0.60) bits |= BIT_SPEED_LOWCONF;
      if (dynDistEnabled && speedForDynDistOk) bits |= BIT_DIST_DYNAMIC;
      else bits |= BIT_DIST_FIXED_FALLBACK;
    }

    // audit invariants
    if (level === 2) {
      bits |= BIT_RED_COMBO_OK;
      if (occlusionRed) bits |= BIT_BOTTOM_OCCLUDED_CLOSE;
      if ((bits & (BIT_TTC | BIT_DIST | BIT_REL)) === 0) bits |= BIT_TTC;
    } else if (preGuardLevel === 2 && level === 1) {
      bits |= BIT_RED_GUARDED;
    }

    const reasonBits = packReasonBits(bits);

    return {
      level,
      riskScore: risk,
      reasonBits,
      state,
      rawRisk,
      allowRed,
      preGuardLevel,
    };
  }

  private thresholdsForMode(mode: number): Thresholds {
    // For simulator baseline, mirror Kotlin defaults for CITY (mode 1) and SPORT (mode 2).
    // Mode 3 (user) should be provided via thresholdsOverride.
    if (mode === 2) {
      return { ttcOrange: 4.0, ttcRed: 2.2, distOrange: 30, distRed: 12, relOrange: 5, relRed: 9 };
    }
    if (mode === 3) {
      // Force explicit override to avoid silently diverging from app prefs.
      return { ttcOrange: 3.0, ttcRed: 2.0, distOrange: 15, distRed: 8, relOrange: 3, relRed: 5 };
    }
    return { ttcOrange: 3.0, ttcRed: 2.0, distOrange: 15, distRed: 8, relOrange: 3, relRed: 5 };
  }

  private scoreLowIsBad(value: number, redThr: number, orangeThr: number): number {
    if (!isFiniteNumber(value) || value <= 0) return 0;
    if (value <= redThr) return 1;
    if (value <= orangeThr) {
      const t = clamp((value - redThr) / Math.max(0.001, (orangeThr - redThr)), 0, 1);
      return 1 - t * 0.55;
    }
    const t = clamp((value - orangeThr) / Math.max(0.001, orangeThr), 0, 1);
    return clamp(0.45 * (1 - t), 0, 0.45);
  }

  private scoreHighIsBad(value: number, orangeThr: number, redThr: number): number {
    if (!isFiniteNumber(value) || value < 0) return 0;
    if (value >= redThr) return 1;
    if (value >= orangeThr) {
      const t = clamp((value - orangeThr) / Math.max(0.001, (redThr - orangeThr)), 0, 1);
      return 0.55 + t * 0.45;
    }
    const t = clamp(value / Math.max(0.001, orangeThr), 0, 1);
    return 0.55 * t;
  }

  private ttcLevelWithHysteresis(ttc: number, orangeOn: number, redOn: number): number {
    if (!isFiniteNumber(ttc) || ttc <= 0) {
      this.lastTtcLevel = 0;
      return 0;
    }
    const redOff = redOn + 0.6;
    const orangeOff = Math.max(orangeOn + 0.9, redOff + 0.2);

    if (this.lastTtcLevel === 2) {
      this.lastTtcLevel = (ttc >= redOff) ? ((ttc <= orangeOn) ? 1 : 0) : 2;
    } else if (this.lastTtcLevel === 1) {
      if (ttc <= redOn) this.lastTtcLevel = 2;
      else if (ttc >= orangeOff) this.lastTtcLevel = 0;
      else this.lastTtcLevel = 1;
    } else {
      if (ttc <= redOn) this.lastTtcLevel = 2;
      else if (ttc <= orangeOn) this.lastTtcLevel = 1;
      else this.lastTtcLevel = 0;
    }

    return this.lastTtcLevel;
  }

  private riskToLevelWithHysteresis(risk: number, conserv: number): number {
    const c = clamp(conserv, 0, 1);
    const orangeOn = 0.45 + 0.17 * c;
    const redOn = 0.75 + 0.07 * c;
    const orangeOff = orangeOn - 0.06;
    const redOff = redOn - 0.05;

    if (this.lastLevel === 2) {
      this.lastLevel = (risk <= redOff) ? ((risk >= orangeOn) ? 1 : 0) : 2;
    } else if (this.lastLevel === 1) {
      if (risk >= redOn) this.lastLevel = 2;
      else if (risk <= orangeOff) this.lastLevel = 0;
      else this.lastLevel = 1;
    } else {
      if (risk >= redOn) this.lastLevel = 2;
      else if (risk >= orangeOn) this.lastLevel = 1;
      else this.lastLevel = 0;
    }

    return this.lastLevel;
  }
}

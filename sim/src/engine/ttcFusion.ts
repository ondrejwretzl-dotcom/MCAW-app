export type TtcFusionResult = {
  ttcFused: number;
  wDist: number;
  mismatchRatio: number;
  sanityActive: boolean;
};

const clamp = (v: number, lo: number, hi: number): number => Math.min(hi, Math.max(lo, v));
const isFinitePos = (v: number | undefined): v is number => Number.isFinite(v) && (v as number) > 0;

export function fuseTtc(
  ttcHeightSec?: number,
  ttcDistSec?: number,
  distanceM: number = Number.NaN,
  approachMps: number = 0,
  bottomOccluded: boolean = false,
  occlConfirmed: boolean = false,
  qualityWeight: number = 1,
): TtcFusionResult {
  void qualityWeight;
  const wDistBase = 0.15;
  const ttcH = isFinitePos(ttcHeightSec) ? ttcHeightSec : undefined;
  const ttcD = isFinitePos(ttcDistSec) ? ttcDistSec : undefined;

  if (!ttcH && !ttcD) return { ttcFused: Number.POSITIVE_INFINITY, wDist: wDistBase, mismatchRatio: Number.NaN, sanityActive: false };
  if (!ttcH) return { ttcFused: ttcD!, wDist: wDistBase, mismatchRatio: Number.NaN, sanityActive: false };
  if (!ttcD) return { ttcFused: ttcH, wDist: wDistBase, mismatchRatio: Number.NaN, sanityActive: false };

  const mismatchRatio = ttcD / ttcH;
  const protectSteady = approachMps <= 0.30;
  const closeEnough = Number.isFinite(distanceM) && distanceM <= 25;
  const mismatchClosing = ttcD < ttcH * 0.70;
  const strongClosing = approachMps >= 0.80;
  const bottomTouchRisk = !!bottomOccluded && Number.isFinite(distanceM) && distanceM <= 12;

  const sanityActive = !protectSteady && (
    (strongClosing && closeEnough && mismatchClosing) ||
    !!occlConfirmed ||
    bottomTouchRisk
  );

  let wDist = wDistBase;
  if (sanityActive) {
    const ratio = ttcH / Math.max(ttcD, 0.05);
    const severity = clamp(((ratio - 1) / 1.5), 0, 1);
    wDist = clamp(wDistBase + severity * 0.60, 0.15, 0.75);
    if (occlConfirmed) wDist = clamp(wDist + 0.10, 0.15, 0.80);
  }

  const wHeight = 1 - wDist;
  let ttcRaw = wHeight * ttcH + wDist * ttcD;
  if (sanityActive) ttcRaw = Math.min(ttcRaw, Math.max(ttcD, 0.05));

  return {
    ttcFused: Number.isFinite(ttcRaw) && ttcRaw > 0 ? ttcRaw : Number.POSITIVE_INFINITY,
    wDist,
    mismatchRatio,
    sanityActive,
  };
}

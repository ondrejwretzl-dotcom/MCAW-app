export const REL_MAX_RATE_MPS = 6.0;
export const DIST_JUMP_GUARD_M = 2.0;
export const K_INVALID_DERIV_FRAMES = 4;

export const INVALID_ID_SWITCH = 1 << 0;
export const INVALID_TRACK_LOST = 1 << 1;
export const INVALID_TRACK_REACQUIRE = 1 << 2;
export const INVALID_OCCL_CHANGE = 1 << 3;
export const INVALID_DIST_GLITCH = 1 << 4;
export const INVALID_SPEED_SOURCE_RESET = 1 << 5;

export type RelFrameCtx = {
  tsMs: number;
  distanceM: number;
  hasBest: boolean;
  bestId?: number;
  bottomOccluded?: boolean;
  riderSpeedKnown: boolean;
};

export type RelFrameOut = {
  distanceStableM: number;
  relSignedEmaMps: number;
  relDerivValid: boolean;
  relInvalidReasonMask: number;
  trendState: number;
  steadyMs: number;
  approachMs: number;
  steadySuppressActive: boolean;
  reenterCooldownMs: number;
  distSlopeEmaMps: number;
};

export class RelStabilityState {
  lastDistanceInputM = Number.NaN;
  lastDistanceInputTsMs = 0;
  lastHadBest = false;
  lastBestId = -1;
  lastBottomOccluded = false;
  prevRiderSpeedKnown = false;
  invalidDerivFramesLeft = 0;
  relInvalidReasonMask = 0;
  prevDistanceForDerivM = Number.NaN;
  prevDerivTsMs = 0;
  relSignedEmaMps = 0;
  relSignedEmaValid = false;
  trendState = 0;
  steadyMs = 0;
  approachMs = 0;
  steadySuppressActive = false;
  reenterCooldownMs = 0;
  distSlopeEmaMps = Number.NaN;

  private trigger(mask: number): void {
    this.invalidDerivFramesLeft = K_INVALID_DERIV_FRAMES;
    this.relInvalidReasonMask |= mask;
    this.prevDistanceForDerivM = Number.NaN;
    this.prevDerivTsMs = 0;
  }

  private stabilizeDistanceInput(distanceM: number, tsMs: number): number {
    if (!Number.isFinite(distanceM)) return distanceM;
    const prev = this.lastDistanceInputM;
    const prevTs = this.lastDistanceInputTsMs;
    if (Number.isFinite(prev) && Math.abs(distanceM - prev) > DIST_JUMP_GUARD_M) {
      this.trigger(INVALID_DIST_GLITCH);
      this.lastDistanceInputM = prev;
      this.lastDistanceInputTsMs = tsMs;
      return prev;
    }
    const out = !Number.isFinite(prev) || prevTs <= 0
      ? distanceM
      : Math.min(prev + REL_MAX_RATE_MPS * Math.min(0.5, Math.max(0.01, (tsMs - prevTs) / 1000)), Math.max(prev - REL_MAX_RATE_MPS * Math.min(0.5, Math.max(0.01, (tsMs - prevTs) / 1000)), distanceM));
    this.lastDistanceInputM = out;
    this.lastDistanceInputTsMs = tsMs;
    return out;
  }

  step(ctx: RelFrameCtx): RelFrameOut {
    if (ctx.hasBest && this.lastHadBest && ctx.bestId !== undefined && ctx.bestId !== this.lastBestId) this.trigger(INVALID_ID_SWITCH);
    if (!ctx.hasBest && this.lastHadBest) this.trigger(INVALID_TRACK_LOST);
    if (ctx.hasBest && !this.lastHadBest) this.trigger(INVALID_TRACK_REACQUIRE);
    if (ctx.bottomOccluded !== undefined && ctx.bottomOccluded !== this.lastBottomOccluded) this.trigger(INVALID_OCCL_CHANGE);
    if (ctx.riderSpeedKnown !== this.prevRiderSpeedKnown) this.trigger(INVALID_SPEED_SOURCE_RESET);

    const distanceStableM = this.stabilizeDistanceInput(ctx.distanceM, ctx.tsMs);

    this.lastHadBest = ctx.hasBest;
    if (ctx.hasBest && ctx.bestId !== undefined) this.lastBestId = ctx.bestId;
    if (ctx.bottomOccluded !== undefined) this.lastBottomOccluded = ctx.bottomOccluded;
    this.prevRiderSpeedKnown = ctx.riderSpeedKnown;

    if (this.invalidDerivFramesLeft > 0) this.invalidDerivFramesLeft -= 1;

    const dtMs = ctx.tsMs - this.prevDerivTsMs;
    const dtOk = this.prevDerivTsMs > 0 && dtMs > 0;
    const dtSec = Math.min(0.5, Math.max(0.01, dtMs / 1000));
    const relDerivValid = this.invalidDerivFramesLeft === 0 && ctx.hasBest && Number.isFinite(distanceStableM) && Number.isFinite(this.prevDistanceForDerivM) && dtOk;

    if (relDerivValid) {
      const sample = (this.prevDistanceForDerivM - distanceStableM) / dtSec;
      this.relSignedEmaMps = !this.relSignedEmaValid ? sample : (this.relSignedEmaMps + 0.30 * (sample - this.relSignedEmaMps));
      this.relSignedEmaValid = true;
      this.relInvalidReasonMask = 0;
      this.prevDistanceForDerivM = distanceStableM;
      this.prevDerivTsMs = ctx.tsMs;
    } else if (this.invalidDerivFramesLeft === 0 && Number.isFinite(distanceStableM) && (!Number.isFinite(this.prevDistanceForDerivM) || this.prevDerivTsMs <= 0)) {
      this.prevDistanceForDerivM = distanceStableM;
      this.prevDerivTsMs = ctx.tsMs;
    }

    const dtMsSafe = Math.max(0, dtMs);
    if (this.reenterCooldownMs > 0 && dtMsSafe > 0) this.reenterCooldownMs = Math.max(0, this.reenterCooldownMs - dtMsSafe);
    const rel = this.relSignedEmaMps;
    const approach = Number.isFinite(rel) && rel > 0.55;
    const recede = Number.isFinite(rel) && rel < -0.55;
    const steady = Number.isFinite(rel) && Math.abs(rel) < 0.35;
    this.trendState = this.trendState === 1 ? (steady ? 0 : 1) : this.trendState === 2 ? (steady ? 0 : 2) : (approach ? 1 : (recede ? 2 : 0));
    if (relDerivValid && Number.isFinite(this.prevDistanceForDerivM) && Number.isFinite(distanceStableM) && dtSec > 0) {
      const slope = (distanceStableM - this.prevDistanceForDerivM) / dtSec;
      this.distSlopeEmaMps = Number.isFinite(this.distSlopeEmaMps) ? (this.distSlopeEmaMps + 0.25 * (slope - this.distSlopeEmaMps)) : slope;
    } else {
      this.distSlopeEmaMps = Number.NaN;
    }
    const approachInd = (Number.isFinite(rel) && rel > 0.55) || (Number.isFinite(this.distSlopeEmaMps) && this.distSlopeEmaMps < -0.25);
    this.approachMs = approachInd ? (this.approachMs + dtMsSafe) : 0;
    const steadyOk = this.trendState === 0 && Number.isFinite(this.distSlopeEmaMps) && Math.abs(this.distSlopeEmaMps) < 0.20 && relDerivValid;
    if (steadyOk) this.steadyMs += dtMsSafe;
    if (!this.steadySuppressActive && this.steadyMs >= 1200 && this.reenterCooldownMs === 0) this.steadySuppressActive = true;
    if (this.approachMs >= 300) {
      this.steadySuppressActive = false;
      this.reenterCooldownMs = 400;
      this.steadyMs = 0;
    }

    return {
      distanceStableM,
      relSignedEmaMps: this.relSignedEmaMps,
      relDerivValid,
      relInvalidReasonMask: this.relInvalidReasonMask,
      trendState: this.trendState,
      steadyMs: this.steadyMs,
      approachMs: this.approachMs,
      steadySuppressActive: this.steadySuppressActive,
      reenterCooldownMs: this.reenterCooldownMs,
      distSlopeEmaMps: this.distSlopeEmaMps,
    };
  }
}

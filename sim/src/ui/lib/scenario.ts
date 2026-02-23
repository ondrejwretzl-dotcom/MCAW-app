import { FrameIn, FrameRow } from './types';
import { Profile, evalProfile } from './profiles';
import { clamp, kmhToMps } from './utils';

export type TtcMode = 'derived' | 'explicit';

export type SegmentDraft = {
  tFromSec: number;
  tToSec: number;
  label: string;

  speedKmh: Profile;         // rider speed
  relMps: Profile;           // approach speed (closing)
  distM: Profile;            // distance (used as initial/override depending on relDriven)
  ttcSec: Profile;           // used when ttcMode=explicit

  roi: Profile;              // 0..1
  qW: Profile;               // 0.6..1.0
  egoOffsetN: Profile;       // 0..2 (rough)

  cutIn: boolean;
  brake: boolean;
  brakeStrength: number;     // 0..1
};

export type ScenarioDraft = {
  scenarioId: string;
  hz: number;
  durationSec: 10 | 30 | 60;
  ttcMode: TtcMode;

  relDrivenDistance: boolean;
  speedNoiseKmh: number;
  speedNoiseSeed: number;

  effectiveMode: number;
  defaultRoi: number;
  defaultQw: number;

  segments: SegmentDraft[];
};

function defaultFrameIn(draft: ScenarioDraft): FrameIn {
  return {
    effectiveMode: draft.effectiveMode,
    distanceM: 30,
    approachSpeedMps: 0,
    ttcSec: 10,
    roiContainment: draft.defaultRoi,
    egoOffsetN: 0,
    cutInActive: false,
    brakeCueActive: false,
    brakeCueStrength: 0,
    qualityWeight: draft.defaultQw,
    riderSpeedMps: kmhToMps(50),
  };
}

function rng(seed: number) {
  // simple deterministic RNG
  let s = (seed >>> 0) || 1;
  return () => {
    s = (s * 1664525 + 1013904223) >>> 0;
    return s / 4294967296;
  };
}

export function generateFrames(draft: ScenarioDraft): FrameRow[] {
  const frames: FrameRow[] = [];
  const hz = Math.max(1, draft.hz);
  const dt = 1 / hz;
  const totalFrames = Math.round(draft.durationSec * hz) + 1;

  // pre-sort segments
  const segs = [...draft.segments].sort((a, b) => a.tFromSec - b.tFromSec);

  const rand = rng(draft.speedNoiseSeed);
  let distIntegrated = segs.length > 0 ? evalProfile(segs[0].distM, 0, Math.max(0, segs[0].tToSec - segs[0].tFromSec)) : 30;

  for (let i = 0; i < totalFrames; i++) {
    const tSec = i * dt;

    // find active segment (last where tFrom <= t < tTo)
    const seg =
      segs.find((s) => tSec >= s.tFromSec && tSec < s.tToSec) ||
      segs[segs.length - 1] ||
      null;

    const base = defaultFrameIn(draft);
    base.distanceM = distIntegrated;

    if (!seg) {
      frames.push({ scenarioId: draft.scenarioId, tSec, in: base });
      continue;
    }

    const segDur = Math.max(0, seg.tToSec - seg.tFromSec);
    const tRel = clamp(tSec - seg.tFromSec, 0, segDur);

    // speed in km/h, with optional noise
    let speedKmh = evalProfile(seg.speedKmh, tRel, segDur);
    if (draft.speedNoiseKmh > 0) {
      // symmetric noise in [-amp, +amp]
      const u = rand() * 2 - 1;
      speedKmh += u * draft.speedNoiseKmh;
    }
    const riderSpeedMps = kmhToMps(Math.max(0, speedKmh));

    const relMps = Math.max(0, evalProfile(seg.relMps, tRel, segDur));
    let distM = evalProfile(seg.distM, tRel, segDur);

    if (draft.relDrivenDistance) {
      // integrate distance from previous distance using rel
      // distance decreases when approaching (rel positive)
      if (i > 0) distIntegrated = Math.max(0, distIntegrated - relMps * dt);
      distM = distIntegrated;
    } else {
      distIntegrated = distM;
    }

    const roi = clamp(evalProfile(seg.roi, tRel, segDur), 0, 1);
    const qW = clamp(evalProfile(seg.qW, tRel, segDur), 0.6, 1.0);
    const egoOffsetN = clamp(evalProfile(seg.egoOffsetN, tRel, segDur), 0, 2);

    let ttcSec = 10;
    if (draft.ttcMode === 'derived') {
      // derived TTC from dist/rel
      const denom = Math.max(relMps, 0.001);
      ttcSec = distM / denom;
    } else {
      ttcSec = Math.max(0.05, evalProfile(seg.ttcSec, tRel, segDur));
    }

    const frameIn: FrameIn = {
      effectiveMode: draft.effectiveMode,
      distanceM: distM,
      approachSpeedMps: relMps,
      ttcSec,
      roiContainment: roi,
      egoOffsetN,
      cutInActive: seg.cutIn,
      brakeCueActive: seg.brake,
      brakeCueStrength: seg.brake ? clamp(seg.brakeStrength, 0, 1) : 0,
      qualityWeight: qW,
      riderSpeedMps,
    };

    frames.push({ scenarioId: draft.scenarioId, tSec, in: frameIn });
  }

  return frames;
}

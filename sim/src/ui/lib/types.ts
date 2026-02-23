export type FrameIn = {
  effectiveMode?: number;
  distanceM: number;
  approachSpeedMps: number; // relative closing speed (m/s), higher = more danger
  ttcSec: number;
  ttcSlopeSecPerSec?: number;
  roiContainment?: number;
  egoOffsetN?: number;
  cutInActive?: boolean;
  brakeCueActive?: boolean;
  brakeCueStrength?: number;
  occlusionCloseFactor?: number;
  occlusionCloseEligible?: boolean;
  qualityWeight?: number;
  riderSpeedMps?: number;
  riderSpeedConfidence?: number;
  egoBrakingConfidence?: number;
  leanDeg?: number;
};

export type FrameOut = {
  level: number;
  riskScore: number;
  reasonBits: number;
};

export type FrameRow = {
  scenarioId: string;
  tSec: number;
  in: FrameIn;
  outKotlin?: FrameOut; // optional if frames come from builder
};

export type EvalRow = {
  tSec: number;
  in: FrameIn;
  out: FrameOut;
  rawRisk?: number;
  emaRisk?: number;
  allowRed?: boolean;
};

export type Thresholds = {
  ttcOrange: number;
  ttcRed: number;
  distOrangeM: number;
  distRedM: number;
  relOrange: number;
  relRed: number;
  orangeOn: number;
  orangeOff: number;
  redOn: number;
  redOff: number;
};

export type WhatIfConfig = {
  enabled: boolean;
  dynamicDistanceEnabled: boolean;
  orangeGapSec: number; // 2.0
  redGapSec: number;    // 1.2
  distOrangeClampMinM: number;
  distOrangeClampMaxM: number;
  distRedClampMinM: number;
  distRedClampMaxM: number;
};

export type DiffRow = {
  tSec: number;
  baseLevel: number;
  tunedLevel: number;
  baseReasonBits: number;
  tunedReasonBits: number;
  baseRisk: number;
  tunedRisk: number;
};

export type ScenarioDoc = {
  scenarioId: string;
  frames: FrameRow[];
  notesMd?: string;
};

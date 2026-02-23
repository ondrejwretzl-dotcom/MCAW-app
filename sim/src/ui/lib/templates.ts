import { ScenarioDraft } from './scenario';
import { Profile } from './profiles';

const H = (v: number): Profile => ({ type: 'hold', value: v });
const L = (a: number, b: number): Profile => ({ type: 'linear', from: a, to: b });

export function templateC2(): ScenarioDraft {
  return {
    scenarioId: 'C2_CITY_JAM_APPROACH_CUSTOM',
    hz: 10,
    durationSec: 10,
    ttcMode: 'derived',
    relDrivenDistance: false,
    speedNoiseKmh: 0,
    speedNoiseSeed: 123,
    effectiveMode: 1,
    defaultRoi: 1,
    defaultQw: 1,
    segments: [
      {
        tFromSec: 0,
        tToSec: 5,
        label: 'steady follow',
        speedKmh: H(50),
        relMps: H(0.5),
        distM: H(35),
        ttcSec: H(6),
        roi: H(1),
        qW: H(1),
        egoOffsetN: H(0),
        cutIn: false,
        brake: false,
        brakeStrength: 0,
      },
      {
        tFromSec: 5,
        tToSec: 10,
        label: 'closing',
        speedKmh: H(50),
        relMps: H(8.0),
        distM: L(35, 10),
        ttcSec: H(2),
        roi: H(1),
        qW: H(1),
        egoOffsetN: H(0),
        cutIn: false,
        brake: false,
        brakeStrength: 0,
      },
    ],
  };
}

export function templateHighway(): ScenarioDraft {
  return {
    scenarioId: 'HIGHWAY_STEADY_FOLLOW',
    hz: 10,
    durationSec: 30,
    ttcMode: 'derived',
    relDrivenDistance: false,
    speedNoiseKmh: 2,
    speedNoiseSeed: 42,
    effectiveMode: 2,
    defaultRoi: 1,
    defaultQw: 1,
    segments: [
      {
        tFromSec: 0,
        tToSec: 30,
        label: 'steady',
        speedKmh: H(120),
        relMps: H(0.8),
        distM: H(60),
        ttcSec: H(10),
        roi: H(1),
        qW: H(1),
        egoOffsetN: H(0),
        cutIn: false,
        brake: false,
        brakeStrength: 0,
      },
    ],
  };
}

export function templateCutIn(): ScenarioDraft {
  return {
    scenarioId: 'CUTIN_EVENT',
    hz: 10,
    durationSec: 10,
    ttcMode: 'derived',
    relDrivenDistance: false,
    speedNoiseKmh: 1,
    speedNoiseSeed: 7,
    effectiveMode: 1,
    defaultRoi: 1,
    defaultQw: 1,
    segments: [
      {
        tFromSec: 0,
        tToSec: 5,
        label: 'steady',
        speedKmh: H(60),
        relMps: H(1.0),
        distM: H(40),
        ttcSec: H(8),
        roi: H(1),
        qW: H(1),
        egoOffsetN: H(0),
        cutIn: false,
        brake: false,
        brakeStrength: 0,
      },
      {
        tFromSec: 5,
        tToSec: 10,
        label: 'cut-in',
        speedKmh: H(60),
        relMps: H(4.0),
        distM: L(40, 18),
        ttcSec: H(3),
        roi: H(0.9),
        qW: H(1),
        egoOffsetN: H(0),
        cutIn: true,
        brake: false,
        brakeStrength: 0,
      },
    ],
  };
}

export function templateEgoBrake(): ScenarioDraft {
  return {
    scenarioId: 'EGO_BRAKING',
    hz: 10,
    durationSec: 10,
    ttcMode: 'derived',
    relDrivenDistance: true,
    speedNoiseKmh: 1,
    speedNoiseSeed: 99,
    effectiveMode: 1,
    defaultRoi: 1,
    defaultQw: 1,
    segments: [
      {
        tFromSec: 0,
        tToSec: 3,
        label: 'approach',
        speedKmh: H(40),
        relMps: H(6.0),
        distM: H(30),
        ttcSec: H(5),
        roi: H(1),
        qW: H(1),
        egoOffsetN: H(0),
        cutIn: false,
        brake: false,
        brakeStrength: 0,
      },
      {
        tFromSec: 3,
        tToSec: 10,
        label: 'ego braking',
        speedKmh: { type: 'accel', start: 40, accelKmhPerSec: -2.0 },
        relMps: H(2.0),
        distM: H(30),
        ttcSec: H(6),
        roi: H(1),
        qW: H(1),
        egoOffsetN: H(0),
        cutIn: false,
        brake: true,
        brakeStrength: 0.8,
      },
    ],
  };
}

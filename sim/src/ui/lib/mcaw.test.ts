import test from 'node:test';
import assert from 'node:assert/strict';

import { sampleProfileFrames, buildMcawSpec, validateMcawSpec } from './mcaw';
import { templateC2 } from './templates';

test('maps hold/linear profiles to sampled frames', () => {
  assert.deepEqual(sampleProfileFrames({ kind: 'hold', value: 3 }, 2, 1), [3, 3, 3]);
  const linear = sampleProfileFrames({ kind: 'linear', from: 0, to: 4 }, 2, 1);
  assert.deepEqual(linear.map((v) => Number(v.toFixed(3))), [0, 2, 4]);
});

test('segment continuity validation fails on overlaps and gaps', () => {
  const draft = templateC2();
  draft.segments = [
    { ...draft.segments[0], tFromSec: 0, tToSec: 5, label: 'a' },
    { ...draft.segments[1], tFromSec: 4, tToSec: 7, label: 'b' },
    { ...draft.segments[1], tFromSec: 9, tToSec: 10, label: 'c' },
  ];
  const { spec } = buildMcawSpec(draft, { title: 'x', notes: 'n', domain: 'CITY', vehicle: 'CAR' });
  const issues = validateMcawSpec(spec, false);
  const errors = issues.filter((i) => i.kind === 'error').map((i) => i.message).join(' | ');
  assert.match(errors, /overlaps/);
  assert.match(errors, /Gap/);
});

test('golden C2_CITY_JAM_APPROACH_CUSTOM transformation', () => {
  const draft = templateC2();
  const { spec, issues } = buildMcawSpec(draft, {
    title: 'C2 City Jam Approach (Custom)',
    notes: '[HUMAN_CONFIRMED] baseline city jam closing approach',
    domain: 'CITY',
    vehicle: 'CAR',
    expectations: [
      { type: 'MustEnterLevelBy', level: 1, latestSecAfterHazard: 1.5, hazardTimeSec: 5.0, message: 'Orange quickly after closing starts.' },
      { type: 'MaxTransitionsInWindow', maxTransitions: 2, windowSec: 3.0, message: 'No alert flapping.' },
    ],
  });

  assert.equal(spec.id, 'C2_CITY_JAM_APPROACH_CUSTOM');
  assert.equal(spec.domain, 'CITY');
  assert.equal(spec.vehicle, 'CAR');
  assert.equal(spec.segments.length, 2);
  assert.equal(spec.segments[0].approachSpeedMps.kind, 'hold');
  assert.equal(spec.segments[1].ttcSec.kind, 'derived');
  assert.equal(issues.filter((i) => i.kind === 'error').length, 0);
});

import { RelStabilityState, K_INVALID_DERIV_FRAMES } from './relStability';
import { RiskEngineRef } from '../../engine/RiskEngine';
import { fuseTtc } from '../../engine/ttcFusion';

test('rel stability invalidates on occlusion exit jump', () => {
  const st = new RelStabilityState();
  for (let i = 0; i < 6; i++) {
    st.step({ tsMs: i * 100, distanceM: 5.0, hasBest: true, bestId: 1, bottomOccluded: true, riderSpeedKnown: true });
  }
  const before = st.relSignedEmaMps;
  const jump = st.step({ tsMs: 600, distanceM: 12.0, hasBest: true, bestId: 1, bottomOccluded: false, riderSpeedKnown: true });
  assert.equal(jump.relDerivValid, false);
  for (let i = 1; i <= K_INVALID_DERIV_FRAMES; i++) {
    const f = st.step({ tsMs: 600 + i * 100, distanceM: 5.0, hasBest: true, bestId: 1, bottomOccluded: false, riderSpeedKnown: true });
    assert.equal(f.relDerivValid, false);
  }
  assert.ok(Math.abs(st.relSignedEmaMps - before) <= 0.5);
});

test('rel stability invalidates on id switch', () => {
  const st = new RelStabilityState();
  for (let i = 0; i < 6; i++) st.step({ tsMs: i * 100, distanceM: 8.0, hasBest: true, bestId: 1, bottomOccluded: false, riderSpeedKnown: true });
  const out = st.step({ tsMs: 700, distanceM: 8.0, hasBest: true, bestId: 2, bottomOccluded: false, riderSpeedKnown: true });
  assert.equal(out.relDerivValid, false);
});

test('rel stability invalidates on distance glitch without mode change', () => {
  const st = new RelStabilityState();
  for (let i = 0; i < 6; i++) st.step({ tsMs: i * 100, distanceM: 7.0, hasBest: true, bestId: 1, bottomOccluded: false, riderSpeedKnown: true });
  const before = st.relSignedEmaMps;
  const out = st.step({ tsMs: 700, distanceM: 10.5, hasBest: true, bestId: 1, bottomOccluded: false, riderSpeedKnown: true });
  assert.equal(out.relDerivValid, false);
  assert.ok(Math.abs(st.relSignedEmaMps - before) <= 0.5);
});


test('EARLY_CLOSING_TTC_MISMATCH reaches caution early with fusion', () => {
  const eng = new RiskEngineRef();
  let cautionAt = Number.POSITIVE_INFINITY;
  for (let i = 0; i < 40; i++) {
    const t = i * 0.1;
    const dist = 20 - i * 0.35;
    const approach = 1.2;
    const fused = fuseTtc(10, 4.2, dist, approach, false, false, 1);
    const out = eng.evaluate({
      tsMs: Math.round(t * 1000),
      effectiveMode: 1,
      distanceM: dist,
      approachSpeedMps: approach,
      ttcSec: fused.ttcFused,
      roiContainment: 1,
      egoOffsetN: 0,
      cutInActive: false,
      brakeCueActive: false,
      brakeCueStrength: 0,
      riderSpeedMps: 12,
      riderSpeedConfidence: 1,
      egoBrakingConfidence: 0,
      leanDeg: Number.NaN,
    });
    if (out.level >= 1) { cautionAt = t; break; }
  }
  assert.ok(cautionAt <= 4.0);
});

test('LATE_CLOSING_TTC_MISMATCH can escalate to red when dist ttc urgent', () => {
  const eng = new RiskEngineRef();
  let maxLevel = 0;
  for (let i = 0; i < 30; i++) {
    const t = i * 0.1;
    const dist = Math.max(1.0, 2.0 - i * 0.03);
    const approach = 1.4;
    const fused = fuseTtc(9.5, 1.6, dist, approach, true, false, 1);
    const out = eng.evaluate({
      tsMs: Math.round(t * 1000),
      effectiveMode: 1,
      distanceM: dist,
      approachSpeedMps: approach,
      ttcSec: fused.ttcFused,
      roiContainment: 1,
      egoOffsetN: 0,
      cutInActive: false,
      brakeCueActive: false,
      brakeCueStrength: 0,
      riderSpeedMps: 10,
      riderSpeedConfidence: 1,
      egoBrakingConfidence: 0,
      leanDeg: Number.NaN,
    });
    maxLevel = Math.max(maxLevel, out.level);
  }
  assert.ok(maxLevel >= 1);
});

test('steady headway mismatch does not force caution', () => {
  const eng = new RiskEngineRef();
  let maxLevel = 0;
  for (let i = 0; i < 40; i++) {
    const fused = fuseTtc(10, 2.5, 8, 0.15, false, false, 1);
    const out = eng.evaluate({
      tsMs: i * 100,
      effectiveMode: 1,
      distanceM: 8,
      approachSpeedMps: 0.15,
      ttcSec: fused.ttcFused,
      roiContainment: 1,
      egoOffsetN: 0,
      cutInActive: false,
      brakeCueActive: false,
      brakeCueStrength: 0,
      riderSpeedMps: 5,
      riderSpeedConfidence: 1,
      egoBrakingConfidence: 0,
      leanDeg: Number.NaN,
    });
    maxLevel = Math.max(maxLevel, out.level);
  }
  assert.equal(maxLevel, 0);
});

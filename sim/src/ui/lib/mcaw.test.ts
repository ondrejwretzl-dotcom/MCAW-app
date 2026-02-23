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

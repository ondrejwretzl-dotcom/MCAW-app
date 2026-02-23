#!/usr/bin/env node
import fs from 'node:fs';
import path from 'node:path';
import process from 'node:process';

import { RiskEngineRef } from '../dist-engine/RiskEngineRef.node.mjs';

// This script expects a FRAME-trace JSONL produced by Kotlin tests.
// Each line must contain: { type:"FRAME", tSec, in:{...}, out:{ level, riskScore, reasonBits } }
//
// Because Vite/TS build is separate, we ship a small prebuilt node module under dist-engine.
// During dev, you can run `node scripts/dev-golden.mjs` (not provided yet) or build engine first.

function usage() {
  console.error('Usage: npm run golden -- <path/to/frame_trace.jsonl>');
  process.exit(2);
}

const file = process.argv[2];
if (!file) usage();

const text = fs.readFileSync(file, 'utf8');
const lines = text.split(/\r?\n/).filter(l => l.trim().length);

const eng = new RiskEngineRef();

const EPS = 1e-3;
let frameCount = 0;

for (let i = 0; i < lines.length; i++) {
  const evt = JSON.parse(lines[i]);
  if (evt.type !== 'FRAME') continue;
  frameCount++;

  const inp = evt.in;
  const outK = evt.out;

  const outTS = eng.evaluate({
    tsMs: Math.round((evt.tSec ?? 0) * 1000),
    effectiveMode: inp.effectiveMode,
    distanceM: inp.distanceM,
    approachSpeedMps: inp.approachSpeedMps,
    ttcSec: inp.ttcSec,
    ttcSlopeSecPerSec: inp.ttcSlopeSecPerSec ?? 0,
    roiContainment: inp.roiContainment,
    egoOffsetN: inp.egoOffsetN,
    cutInActive: !!inp.cutInActive,
    brakeCueActive: !!inp.brakeCueActive,
    brakeCueStrength: inp.brakeCueStrength ?? 0,
    occlusionCloseFactor: inp.occlusionCloseFactor ?? 0,
    occlusionCloseEligible: !!inp.occlusionCloseEligible,
    qualityWeight: inp.qualityWeight ?? 1,
    riderSpeedMps: inp.riderSpeedMps,
    riderSpeedConfidence: inp.riderSpeedConfidence ?? 1,
    egoBrakingConfidence: inp.egoBrakingConfidence ?? 0,
    leanDeg: inp.leanDeg ?? Number.NaN,
  });

  const dRisk = Math.abs((outTS.riskScore ?? 0) - (outK.riskScore ?? 0));
  const ok =
    outTS.level === outK.level &&
    (outTS.reasonBits >>> 0) === (outK.reasonBits >>> 0) &&
    dRisk <= EPS;

  if (!ok) {
    console.error('GOLDEN MISMATCH at line', i + 1, 'frame', frameCount);
    console.error('tSec:', evt.tSec);
    console.error('Input:', inp);
    console.error('Kotlin out:', outK);
    console.error('TS out:', { level: outTS.level, riskScore: outTS.riskScore, reasonBits: outTS.reasonBits });
    console.error('risk diff:', dRisk);
    process.exit(1);
  }
}

console.log('OK:', frameCount, 'frames matched.');

import { downloadText, toCsv, round3 } from './utils';
import { EvalRow, ScenarioDraft, WhatIfConfig } from './types';
import { WarningItem } from './validate';

export function downloadEvalCsv(filenameBase: string, rows: any[]) {
  downloadText(`${filenameBase}.csv`, toCsv(rows), 'text/csv');
}

export function buildScenarioMd(args: {
  draft: any;
  whatIf: WhatIfConfig;
  baselineFirstOrange?: number | null;
  baselineFirstRed?: number | null;
  tunedFirstOrange?: number | null;
  tunedFirstRed?: number | null;
  warnings: WarningItem[];
}): string {
  const { draft, whatIf, baselineFirstOrange, baselineFirstRed, tunedFirstOrange, tunedFirstRed, warnings } = args;

  const lines: string[] = [];
  lines.push(`# Scenario: ${draft.scenarioId}`);
  lines.push('');
  lines.push(`- duration: **${draft.durationSec}s**`);
  lines.push(`- hz: **${draft.hz}**`);
  lines.push(`- ttcMode: **${draft.ttcMode}**`);
  lines.push(`- relDrivenDistance: **${draft.relDrivenDistance ? 'ON' : 'OFF'}**`);
  lines.push(`- speedNoise: **±${draft.speedNoiseKmh} km/h** (seed=${draft.speedNoiseSeed})`);
  lines.push('');

  lines.push(`## What-if`);
  lines.push(`- enabled: **${whatIf.enabled ? 'ON' : 'OFF'}**`);
  lines.push(`- dynamicDistanceEnabled: **${whatIf.dynamicDistanceEnabled ? 'ON' : 'OFF'}**`);
  if (whatIf.dynamicDistanceEnabled) {
    lines.push(`- gaps: orange=${whatIf.orangeGapSec}s, red=${whatIf.redGapSec}s`);
    lines.push(`- clamp orange(m): ${whatIf.distOrangeClampMinM}..${whatIf.distOrangeClampMaxM}`);
    lines.push(`- clamp red(m): ${whatIf.distRedClampMinM}..${whatIf.distRedClampMaxM}`);
  }
  lines.push('');

  lines.push(`## Timings`);
  lines.push(`- baseline first ORANGE: ${baselineFirstOrange == null ? '—' : `${round3(baselineFirstOrange)}s`}`);
  lines.push(`- baseline first RED: ${baselineFirstRed == null ? '—' : `${round3(baselineFirstRed)}s`}`);
  lines.push(`- what-if first ORANGE: ${tunedFirstOrange == null ? '—' : `${round3(tunedFirstOrange)}s`}`);
  lines.push(`- what-if first RED: ${tunedFirstRed == null ? '—' : `${round3(tunedFirstRed)}s`}`);
  lines.push('');

  lines.push(`## Segments`);
  lines.push(`| tFrom | tTo | label | speed | rel | dist | ROI | qW | cutIn | brake | brakeStrength |`);
  lines.push(`|---:|---:|---|---|---|---|---:|---:|---:|---:|---:|`);
  for (const s of draft.segments) {
    lines.push(`| ${s.tFromSec} | ${s.tToSec} | ${s.label} | ${profileToStr(s.speedKmh)} | ${profileToStr(s.relMps)} | ${profileToStr(s.distM)} | ${profileToStr(s.roi)} | ${profileToStr(s.qW)} | ${s.cutIn ? 1 : 0} | ${s.brake ? 1 : 0} | ${s.brakeStrength} |`);
  }
  lines.push('');

  if (warnings.length > 0) {
    lines.push(`## Warnings`);
    for (const item of warnings) {
      lines.push(`- **${item.kind.toUpperCase()}**: ${item.message}`);
    }
    lines.push('');
  }

  return lines.join('\n');
}

function profileToStr(p: any): string {
  if (!p) return '';
  if (p.type === 'hold') return `hold(${p.value})`;
  if (p.type === 'linear') return `linear(${p.from}→${p.to})`;
  if (p.type === 'accel') return `accel(start=${p.start}, a=${p.accelKmhPerSec} km/h/s)`;
  return String(p);
}

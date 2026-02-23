import { evalProfile } from './profiles';
import { ScenarioDraft } from './scenario';
import { WhatIfConfig } from './types';

export type WarningItem = { kind: 'error' | 'warn'; message: string };

export function validateScenario(draft: ScenarioDraft, whatIf: WhatIfConfig): WarningItem[] {
  const w: WarningItem[] = [];
  if (!draft.scenarioId.trim()) w.push({ kind: 'error', message: 'Scenario ID is empty.' });

  const segs = [...draft.segments].sort((a, b) => a.tFromSec - b.tFromSec);
  for (let i = 0; i < segs.length; i++) {
    const s = segs[i];
    if (!(s.tToSec > s.tFromSec)) w.push({ kind: 'error', message: `Segment "${s.label}" has invalid time range (tFrom >= tTo).` });
    if (i > 0) {
      const prev = segs[i - 1];
      if (s.tFromSec < prev.tToSec) w.push({ kind: 'warn', message: `Segment "${s.label}" overlaps previous segment.` });
      if (s.tFromSec > prev.tToSec) w.push({ kind: 'warn', message: `Gap between "${prev.label}" and "${s.label}" (no segment defined).` });
    }

    const dur = Math.max(0.001, s.tToSec - s.tFromSec);
    const rel0 = evalProfile(s.relMps, 0, dur);
    const rel1 = evalProfile(s.relMps, dur, dur);
    const dist0 = evalProfile(s.distM, 0, dur);
    const dist1 = evalProfile(s.distM, dur, dur);
    const speed0 = evalProfile(s.speedKmh, 0, dur);
    const speed1 = evalProfile(s.speedKmh, dur, dur);

    if (speed0 < 0 || speed1 < 0) {
      w.push({ kind: 'error', message: `Segment "${s.label}": speed is negative. To je fyzikálně nemožné pro tuto simulaci.` });
    }
    if (rel0 < 0 || rel1 < 0) {
      w.push({ kind: 'error', message: `Segment "${s.label}": rel < 0. Model počítá jen uzavírání vzdálenosti (approach >= 0).` });
    }
    if (!draft.relDrivenDistance && (dist0 < 0 || dist1 < 0)) {
      w.push({ kind: 'error', message: `Segment "${s.label}": distance < 0. Záporná vzdálenost je nevalidní.` });
    }

    if (draft.ttcMode === 'explicit') {
      const ttc0 = evalProfile(s.ttcSec, 0, dur);
      const ttc1 = evalProfile(s.ttcSec, dur, dur);
      if (ttc0 <= 0 || ttc1 <= 0) {
        w.push({ kind: 'error', message: `Segment "${s.label}": explicit TTC musí být > 0.` });
      }

      const refRel = Math.max(0.2, (rel0 + rel1) / 2);
      const expectedDistMid = refRel * ((ttc0 + ttc1) / 2);
      const distMid = (dist0 + dist1) / 2;
      const ratio = expectedDistMid > 0 ? distMid / expectedDistMid : 1;
      if (!draft.relDrivenDistance && (ratio < 0.25 || ratio > 4)) {
        w.push({
          kind: 'warn',
          message: `Segment "${s.label}": explicit TTC je v silném rozporu s dist/rel (oček. dist≈rel*TTC). Doporučení: použít TTC mode=derived nebo upravit parametry.`,
        });
      }
    }
  }

  if (draft.ttcMode === 'derived') {
    w.push({ kind: 'warn', message: 'TTC derived = dist/rel. Make sure rel is not near 0, otherwise TTC will be huge.' });
  }

  if (draft.relDrivenDistance) {
    w.push({ kind: 'warn', message: 'REL-driven distance is ON: distance is integrated from rel. Distance profiles inside segments will be ignored (except initial).' });
  }

  if (whatIf.enabled && whatIf.dynamicDistanceEnabled) {
    if (!(whatIf.orangeGapSec > whatIf.redGapSec)) w.push({ kind: 'error', message: 'orangeGapSec must be > redGapSec.' });
    if (!(whatIf.distOrangeClampMinM < whatIf.distOrangeClampMaxM)) w.push({ kind: 'error', message: 'Orange clamp min must be < max.' });
    if (!(whatIf.distRedClampMinM < whatIf.distRedClampMaxM)) w.push({ kind: 'error', message: 'Red clamp min must be < max.' });
  }

  return w;
}

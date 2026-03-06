import React from 'react';
import { round3 } from '../lib/utils';
import { InfoTip } from './InfoTip';

export type TableRow = {
  tSec: number;
  distanceM: number;
  relMps: number;
  relSignedSampleMps?: number;
  relDerivValid?: boolean;
  relSignedEmaMps?: number;
  ttcSec: number;
  ttcHeightSec?: number;
  ttcDistSec?: number;
  boxHeightPx?: number;
  trackedPresent?: boolean;
  bottomOccluded?: boolean;
  occlConfirmed?: boolean;
  suppressRecedingHard?: boolean;
  suppressSteadyGapHard?: boolean;
  speedKmh: number;

  referenceRisk: number;
  referenceLevel: number;
  referenceReasonBits: number;

  simulatedRisk?: number;
  simulatedLevel?: number;
  simulatedReasonBits?: number;

  distOrangeM?: number;
  distRedM?: number;
};

export function ResultsTable(props: {
  rows: TableRow[];
  showOnlyDiffs: boolean;
  onToggleOnlyDiffs: (v: boolean) => void;
  hasComparison: boolean;
}) {
  const { rows, showOnlyDiffs, onToggleOnlyDiffs, hasComparison } = props;

  const filtered = !hasComparison || !showOnlyDiffs
    ? rows
    : rows.filter((r) => r.simulatedLevel !== undefined && (r.referenceLevel !== r.simulatedLevel || r.referenceReasonBits !== r.simulatedReasonBits));

  return (
    <div style={{ marginTop: 12 }}>
      {hasComparison && (
        <label style={{ display: 'flex', gap: 8, alignItems: 'center', marginBottom: 8 }}>
          Show only diffs
          <input type="checkbox" checked={showOnlyDiffs} onChange={(e) => onToggleOnlyDiffs(e.target.checked)} />
        </label>
      )}

      <div style={{ overflow: 'auto', maxHeight: 420, border: '1px solid #eee', borderRadius: 10 }}>
        <table style={{ borderCollapse: 'collapse', minWidth: 1400, width: '100%' }}>
          <thead>
            <tr>
              {[
                { key: 'tSec', label: 't(s)', info: 'Čas snímku v sekundách od začátku scénáře.' },
                { key: 'distanceM', label: 'dist(m)', info: 'Vzdálenost použitá do RiskEngine po případném vyhlazení.' },
                { key: 'relMps', label: 'relEma(m/s)', info: 'EMA relativní rychlosti. Kladné = přibližování, záporné = vzdalování.' },
                { key: 'relSignedSampleMps', label: 'relRaw(m/s)', info: 'Surový derivovaný vzorek relativní rychlosti z distance mezi snímky.' },
                { key: 'relDerivValid', label: 'relValid', info: 'Zda je derivace REL už zahřátá a použitelná.' },
                { key: 'relSignedEmaMps', label: 'relEmaRaw', info: 'Surová EMA hodnota REL z pipeline / trace, pokud je k dispozici.' },
                { key: 'ttcSec', label: 'ttcFused(s)', info: 'Finální TTC použitý do RiskEngine po fusion/bridge.' },
                { key: 'ttcHeightSec', label: 'ttcHeight(s)', info: 'TTC z image trendu / výšky bboxu. Když je neplatné, bývá prázdné nebo NaN.' },
                { key: 'ttcDistSec', label: 'ttcDist(s)', info: 'Pomocné TTC z distance a REL. U E2E slouží jako fallback / diagnostika.' },
                { key: 'boxHeightPx', label: 'boxH(px)', info: 'Výška bboxu v pixelech. Nula / propad typicky znamená výpadek height TTC.' },
                { key: 'trackedPresent', label: 'tracked', info: 'Zda tracker považuje target za přítomný.' },
                { key: 'bottomOccluded', label: 'bottomOcc', info: 'Spodní část bboxu je okludovaná.' },
                { key: 'occlConfirmed', label: 'occConf', info: 'Okluze je potvrzená stabilněji než jednorázový šum.' },
                { key: 'suppressRecedingHard', label: 'supRec', info: 'Hard suppress pro vzdalující se target.' },
                { key: 'suppressSteadyGapHard', label: 'supGap', info: 'Hard suppress pro stabilní mezeru / follow.' },
                { key: 'speedKmh', label: 'speed(km/h)', info: 'Rychlost jezdce / ego vozidla.' },
                { key: 'referenceRisk', label: 'refRisk', info: 'Referenční risk. Při uploadu bez override je to Kotlin / CI výstup. V builderu je to defaultní simulace.' },
                { key: 'referenceLevel', label: 'refLvl', info: 'Referenční alert level.' },
                { key: 'referenceReasonBits', label: 'refReason', info: 'Referenční bitové reason ID.' },
                ...(hasComparison ? [
                  { key: 'simulatedRisk', label: 'simRisk', info: 'Alternativní risk z TS simulace. Při uploadu bez override ukazuje parity přepočet; při override ukazuje what-if variantu.' },
                  { key: 'simulatedLevel', label: 'simLvl', info: 'Alternativní alert level z TS simulace.' },
                  { key: 'simulatedReasonBits', label: 'simReason', info: 'Alternativní reason ID z TS simulace.' },
                  { key: 'distOrangeM', label: 'distOrangeM', info: 'Efektivní ORANGE distance threshold v simulaci.' },
                  { key: 'distRedM', label: 'distRedM', info: 'Efektivní RED distance threshold v simulaci.' },
                ] : []),
              ].map((h) => (
                <th key={h.key} style={{ textAlign: 'left', borderBottom: '1px solid #ddd', padding: '6px 8px', whiteSpace: 'nowrap', position: 'sticky', top: 0, background: '#fff', zIndex: 1 }}>
                  <span style={{ display: 'inline-flex', alignItems: 'center', gap: 4 }}>{h.label}<InfoTip text={h.info} /></span>
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {filtered.map((r, idx) => (
              <tr key={idx} style={{ borderBottom: '1px solid #f0f0f0' }}>
                <td style={{ padding: '6px 8px' }}>{round3(r.tSec)}</td>
                <td style={{ padding: '6px 8px' }}>{round3(r.distanceM)}</td>
                <td style={{ padding: '6px 8px' }}>{Number.isFinite(r.relMps) ? round3(r.relMps) : '—'}</td>
                <td style={{ padding: '6px 8px' }}>{r.relSignedSampleMps != null && Number.isFinite(r.relSignedSampleMps) ? round3(r.relSignedSampleMps) : '—'}</td>
                <td style={{ padding: '6px 8px' }}>{r.relDerivValid == null ? '' : (r.relDerivValid ? 'Y' : 'N')}</td>
                <td style={{ padding: '6px 8px' }}>{r.relSignedEmaMps != null && Number.isFinite(r.relSignedEmaMps) ? round3(r.relSignedEmaMps) : '—'}</td>
                <td style={{ padding: '6px 8px' }}>{Number.isFinite(r.ttcSec) ? round3(r.ttcSec) : '—'}</td>
                <td style={{ padding: '6px 8px' }}>{r.ttcHeightSec != null && Number.isFinite(r.ttcHeightSec) ? round3(r.ttcHeightSec) : '—'}</td>
                <td style={{ padding: '6px 8px' }}>{r.ttcDistSec != null && Number.isFinite(r.ttcDistSec) ? round3(r.ttcDistSec) : '—'}</td>
                <td style={{ padding: '6px 8px' }}>{r.boxHeightPx != null && Number.isFinite(r.boxHeightPx) ? round3(r.boxHeightPx) : '—'}</td>
                <td style={{ padding: '6px 8px' }}>{r.trackedPresent == null ? '' : (r.trackedPresent ? 'Y' : 'N')}</td>
                <td style={{ padding: '6px 8px' }}>{r.bottomOccluded == null ? '' : (r.bottomOccluded ? 'Y' : 'N')}</td>
                <td style={{ padding: '6px 8px' }}>{r.occlConfirmed == null ? '' : (r.occlConfirmed ? 'Y' : 'N')}</td>
                <td style={{ padding: '6px 8px' }}>{r.suppressRecedingHard == null ? '' : (r.suppressRecedingHard ? 'Y' : 'N')}</td>
                <td style={{ padding: '6px 8px' }}>{r.suppressSteadyGapHard == null ? '' : (r.suppressSteadyGapHard ? 'Y' : 'N')}</td>
                <td style={{ padding: '6px 8px' }}>{round3(r.speedKmh)}</td>
                <td style={{ padding: '6px 8px' }}>{round3(r.referenceRisk)}</td>
                <td style={{ padding: '6px 8px' }}>{r.referenceLevel}</td>
                <td style={{ padding: '6px 8px' }}>{r.referenceReasonBits}</td>
                {hasComparison && (
                  <>
                    <td style={{ padding: '6px 8px' }}>{r.simulatedRisk == null ? '' : round3(r.simulatedRisk)}</td>
                    <td style={{ padding: '6px 8px' }}>{r.simulatedLevel == null ? '' : r.simulatedLevel}</td>
                    <td style={{ padding: '6px 8px' }}>{r.simulatedReasonBits == null ? '' : r.simulatedReasonBits}</td>
                    <td style={{ padding: '6px 8px' }}>{r.distOrangeM == null || !Number.isFinite(r.distOrangeM) ? '' : round3(r.distOrangeM)}</td>
                    <td style={{ padding: '6px 8px' }}>{r.distRedM == null || !Number.isFinite(r.distRedM) ? '' : round3(r.distRedM)}</td>
                  </>
                )}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <div style={{ marginTop: 6, color: '#666' }}>Rows: {filtered.length}</div>
    </div>
  );
}

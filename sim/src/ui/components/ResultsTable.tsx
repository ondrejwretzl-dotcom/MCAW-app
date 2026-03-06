import React from 'react';
import { round3 } from '../lib/utils';
import { InfoTip } from './InfoTip';

export type TableRow = {
  tSec: number;
  distanceM: number;
  relMps: number;
  relSignedSampleMps?: number;
  relDerivValid?: boolean;
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

  baseRisk: number;
  baseLevel: number;
  baseReasonBits: number;

  tunedRisk?: number;
  tunedLevel?: number;
  tunedReasonBits?: number;

  distOrangeM?: number;
  distRedM?: number;
};

export function ResultsTable(props: {
  rows: TableRow[];
  showOnlyDiffs: boolean;
  onToggleOnlyDiffs: (v: boolean) => void;
  hasTuned: boolean;
}) {
  const { rows, showOnlyDiffs, onToggleOnlyDiffs, hasTuned } = props;

  const filtered = !hasTuned || !showOnlyDiffs
    ? rows
    : rows.filter((r) => r.tunedLevel !== undefined && (r.baseLevel !== r.tunedLevel || r.baseReasonBits !== r.tunedReasonBits));

  return (
    <div style={{ marginTop: 12 }}>
      {hasTuned && (
        <label style={{ display: 'flex', gap: 8, alignItems: 'center', marginBottom: 8 }}>
          Show only diffs
          <input type="checkbox" checked={showOnlyDiffs} onChange={(e) => onToggleOnlyDiffs(e.target.checked)} />
        </label>
      )}

      <div style={{ overflow: 'auto', maxHeight: 420, border: '1px solid #eee', borderRadius: 10 }}>
        <table style={{ borderCollapse: 'collapse', minWidth: 1200, width: '100%' }}>
          <thead>
            <tr>
              {[
                { key: 'tSec', label: 't(s)', info: 'Čas snímku v sekundách od začátku scénáře.' },
                { key: 'distanceM', label: 'dist(m)', info: 'Vzdálenost použitá do RiskEngine po případném vyhlazení.' },
                { key: 'relMps', label: 'relEma(m/s)', info: 'EMA relativní rychlosti. Kladné = přibližování, záporné = vzdalování.' },
                { key: 'relSignedSampleMps', label: 'relRaw(m/s)', info: 'Surový derivovaný vzorek relativní rychlosti z distance mezi snímky.' },
                { key: 'relDerivValid', label: 'relValid', info: 'Zda je derivace REL už zahřátá a použitelná.' },
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
                { key: 'baseRisk', label: 'baseRisk', info: 'Risk score základního enginu.' },
                { key: 'baseLevel', label: 'baseLvl', info: 'Alert level základního enginu.' },
                { key: 'baseReasonBits', label: 'baseReason', info: 'Bitové reason ID základního enginu.' },
                ...(hasTuned ? [
                  { key: 'tunedRisk', label: 'tunedRisk', info: 'Risk score v CUSTOM režimu.' },
                  { key: 'tunedLevel', label: 'tunedLvl', info: 'Alert level v CUSTOM režimu.' },
                  { key: 'tunedReasonBits', label: 'tunedReason', info: 'Reason ID v CUSTOM režimu.' },
                  { key: 'distOrangeM', label: 'distOrangeM', info: 'Efektivní ORANGE distance threshold v CUSTOM režimu.' },
                  { key: 'distRedM', label: 'distRedM', info: 'Efektivní RED distance threshold v CUSTOM režimu.' },
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
                <td style={{ padding: '6px 8px' }}>{Number.isFinite(r.relMps) ? round3(r.relMps) : "—"}</td>
                <td style={{ padding: '6px 8px' }}>{r.relSignedSampleMps != null && Number.isFinite(r.relSignedSampleMps) ? round3(r.relSignedSampleMps) : "—"}</td>
                <td style={{ padding: '6px 8px' }}>{r.relDerivValid == null ? '' : (r.relDerivValid ? 'Y' : 'N')}</td>
                <td style={{ padding: '6px 8px' }}>{round3(r.ttcSec)}</td>
                <td style={{ padding: '6px 8px' }}>{r.ttcHeightSec != null && Number.isFinite(r.ttcHeightSec) ? round3(r.ttcHeightSec) : "—"}</td>
                <td style={{ padding: '6px 8px' }}>{r.ttcDistSec != null && Number.isFinite(r.ttcDistSec) ? round3(r.ttcDistSec) : "—"}</td>
                <td style={{ padding: '6px 8px' }}>{r.boxHeightPx != null && Number.isFinite(r.boxHeightPx) ? round3(r.boxHeightPx) : "—"}</td>
                <td style={{ padding: '6px 8px' }}>{r.trackedPresent == null ? '' : (r.trackedPresent ? 'Y' : 'N')}</td>
                <td style={{ padding: '6px 8px' }}>{r.bottomOccluded == null ? '' : (r.bottomOccluded ? 'Y' : 'N')}</td>
                <td style={{ padding: '6px 8px' }}>{r.occlConfirmed == null ? '' : (r.occlConfirmed ? 'Y' : 'N')}</td>
                <td style={{ padding: '6px 8px' }}>{r.suppressRecedingHard == null ? '' : (r.suppressRecedingHard ? 'Y' : 'N')}</td>
                <td style={{ padding: '6px 8px' }}>{r.suppressSteadyGapHard == null ? '' : (r.suppressSteadyGapHard ? 'Y' : 'N')}</td>
                <td style={{ padding: '6px 8px' }}>{round3(r.speedKmh)}</td>
                <td style={{ padding: '6px 8px' }}>{round3(r.baseRisk)}</td>
                <td style={{ padding: '6px 8px' }}>{r.baseLevel}</td>
                <td style={{ padding: '6px 8px' }}>{r.baseReasonBits}</td>

                {hasTuned && (
                  <>
                    <td style={{ padding: '6px 8px' }}>{r.tunedRisk == null ? '' : round3(r.tunedRisk)}</td>
                    <td style={{ padding: '6px 8px' }}>{r.tunedLevel == null ? '' : r.tunedLevel}</td>
                    <td style={{ padding: '6px 8px' }}>{r.tunedReasonBits == null ? '' : r.tunedReasonBits}</td>
                    <td style={{ padding: '6px 8px' }}>{r.distOrangeM == null ? '' : round3(r.distOrangeM)}</td>
                    <td style={{ padding: '6px 8px' }}>{r.distRedM == null ? '' : round3(r.distRedM)}</td>
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

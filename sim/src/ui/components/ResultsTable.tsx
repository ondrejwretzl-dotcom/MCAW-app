import React from 'react';
import { round3 } from '../lib/utils';

export type TableRow = {
  tSec: number;
  distanceM: number;
  relMps: number;
  ttcSec: number;
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
                't(s)',
                'dist(m)',
                'rel(m/s)',
                'ttc(s)',
                'speed(km/h)',
                'baseRisk',
                'baseLvl',
                'baseReason',
                hasTuned ? 'tunedRisk' : '',
                hasTuned ? 'tunedLvl' : '',
                hasTuned ? 'tunedReason' : '',
                hasTuned ? 'distOrangeM' : '',
                hasTuned ? 'distRedM' : '',
              ]
                .filter(Boolean)
                .map((h) => (
                  <th key={h} style={{ textAlign: 'left', borderBottom: '1px solid #ddd', padding: '6px 8px', whiteSpace: 'nowrap', position: 'sticky', top: 0, background: '#fff', zIndex: 1 }}>
                    {h}
                  </th>
                ))}
            </tr>
          </thead>
          <tbody>
            {filtered.map((r, idx) => (
              <tr key={idx} style={{ borderBottom: '1px solid #f0f0f0' }}>
                <td style={{ padding: '6px 8px' }}>{round3(r.tSec)}</td>
                <td style={{ padding: '6px 8px' }}>{round3(r.distanceM)}</td>
                <td style={{ padding: '6px 8px' }}>{round3(r.relMps)}</td>
                <td style={{ padding: '6px 8px' }}>{round3(r.ttcSec)}</td>
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

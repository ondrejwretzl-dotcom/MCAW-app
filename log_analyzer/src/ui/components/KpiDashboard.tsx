import React from 'react';
import { REL_INVALID_REASON_BITS } from '../../parser/schema';
import type { FalseRedResult, RelQualityResult, StandingSuppressorResult, SwitchBeneficialResult, TtcMismatchResult } from '../../types';

export function KpiDashboard({
  rel,
  falseRed,
  switches,
  standing,
  ttcMismatch,
}: {
  rel: RelQualityResult;
  falseRed: FalseRedResult;
  switches: SwitchBeneficialResult;
  standing: StandingSuppressorResult;
  ttcMismatch: TtcMismatchResult;
}) {
  return (
    <section className="card">
      <h2>Celologová KPI analytika</h2>
      <div className="kpiGrid four">
        <div>
          <h3>REL quality</h3>
          <p>invalid ratio: {(rel.invalidRatio * 100).toFixed(1)}% ({rel.invalidCount}/{rel.totalRelevant})</p>
          {rel.byBit.map((b) => (
            <div key={b.bit} className="barRow" title={REL_INVALID_REASON_BITS.find((x) => x.bit === b.bit)?.text || 'unknown'}>
              <span>bit {b.bit}</span>
              <div className="bar"><i style={{ width: `${Math.max(4, (b.count / Math.max(1, rel.invalidCount)) * 100)}%` }} /></div>
              <span>{b.count}</span>
            </div>
          ))}
        </div>

        <div>
          <h3>False-red (bottom touch)</h3>
          <p className="mono">{falseRed.formula}</p>
          <p>Before: {falseRed.before.falseRed}/{falseRed.before.redBottomTouch} ({(falseRed.before.rate * 100).toFixed(1)}%)</p>
          <p>After: {falseRed.after.falseRed}/{falseRed.after.redBottomTouch} ({(falseRed.after.rate * 100).toFixed(1)}%)</p>
        </div>

        <div>
          <h3>Switch beneficial rate</h3>
          <p>{switches.beneficial}/{switches.total} ({(switches.rate * 100).toFixed(1)}%)</p>
          <small>beneficial = lower TTC variance in +2s window</small>
        </div>

        <div>
          <h3>Standing suppressor</h3>
          <p>missed-critical near stop: {standing.missedCriticalNearStop}/{standing.taggedNearStopCritical} ({(standing.rate * 100).toFixed(1)}%)</p>
          <small>based on imported manual scene tags</small>
        </div>

        <div>
          <h3>TTC mismatch</h3>
          <p>events: {ttcMismatch.events}</p>
          <small>top windows: {ttcMismatch.windows.slice(0, 3).map((w) => `${new Date(w.tsStart).toLocaleTimeString('cs-CZ', { hour12: false })}(${w.count})`).join(', ') || '-'}</small>
        </div>

      </div>
    </section>
  );
}

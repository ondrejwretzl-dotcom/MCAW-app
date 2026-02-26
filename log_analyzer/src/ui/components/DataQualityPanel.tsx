import React from 'react';
import type { ParsedLogData } from '../../types';

export function DataQualityPanel({ data }: { data?: ParsedLogData }) {
  if (!data) return null;
  const riskRows = data.riskRows;
  const speedAvail = riskRows.length > 0
    ? (riskRows.filter((r) => r.riderSpeedMps != null && Number.isFinite(r.riderSpeedMps)).length / riskRows.length) * 100
    : 0;
  const avgConfRows = riskRows.filter((r) => r.riderSpeedConfidence != null && Number.isFinite(r.riderSpeedConfidence));
  const avgConf = avgConfRows.length > 0
    ? avgConfRows.reduce((a, b) => a + (b.riderSpeedConfidence ?? 0), 0) / avgConfRows.length
    : 0;
  const methodDist = new Map<number, number>();
  riskRows.forEach((r) => {
    if (r.riderSpeedMethod == null) return;
    methodDist.set(r.riderSpeedMethod, (methodDist.get(r.riderSpeedMethod) ?? 0) + 1);
  });

  return (
    <section className="card">
      <h2>Data quality</h2>
      <div className="kpiGrid">
        <div><b>Valid rows:</b> {data.dataQuality.validRows}</div>
        <div><b>Partial rows:</b> {data.dataQuality.partialRows}</div>
        <div><b>Rejected rows:</b> {data.dataQuality.rejectedRows}</div>
        <div><b>Rider speed availability:</b> {speedAvail.toFixed(1)}%</div>
        <div><b>Avg speed confidence:</b> {avgConf.toFixed(3)}</div>
        <div><b>Speed contract:</b> {data.schemaSummary.hasRiderSpeedContract ? 'present' : 'missing'}</div>
      </div>
      <div className="chips">
        {Object.entries(data.dataQuality.rejectedReasons).map(([k, v]) => (
          <span className="chip err" key={k}>{k}: {v}</span>
        ))}
        {[...methodDist.entries()].map(([method, count]) => (
          <span className="chip" key={method}>method {method}: {count}</span>
        ))}
      </div>
    </section>
  );
}

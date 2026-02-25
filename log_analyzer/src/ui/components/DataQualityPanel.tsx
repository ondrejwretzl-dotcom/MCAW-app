import React from 'react';
import type { ParsedLogData } from '../../types';

export function DataQualityPanel({ data }: { data?: ParsedLogData }) {
  if (!data) return null;
  return (
    <section className="card">
      <h2>Data quality</h2>
      <div className="kpiGrid">
        <div><b>Valid rows:</b> {data.dataQuality.validRows}</div>
        <div><b>Partial rows:</b> {data.dataQuality.partialRows}</div>
        <div><b>Rejected rows:</b> {data.dataQuality.rejectedRows}</div>
      </div>
      <div className="chips">
        {Object.entries(data.dataQuality.rejectedReasons).map(([k, v]) => (
          <span className="chip err" key={k}>{k}: {v}</span>
        ))}
      </div>
    </section>
  );
}

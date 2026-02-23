import React from 'react';
import { WarningItem } from '../lib/validate';

export function WarningsPanel({ warnings }: { warnings: WarningItem[] }) {
  if (!warnings || warnings.length === 0) return null;
  return (
    <div style={{ padding: 10, border: '1px solid #f3c', background: '#fff7fb', marginTop: 10 }}>
      <div style={{ fontWeight: 700, marginBottom: 6 }}>Scenario warnings</div>
      <ul style={{ margin: 0, paddingLeft: 18 }}>
        {warnings.map((w, i) => (
          <li key={i}>
            <b>{w.kind.toUpperCase()}</b>: {w.message}
          </li>
        ))}
      </ul>
    </div>
  );
}

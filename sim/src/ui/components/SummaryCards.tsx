import React from 'react';
import { round3 } from '../lib/utils';

function Card({ title, lines }: { title: string; lines: string[] }) {
  return (
    <div style={{ border: '1px solid #eee', borderRadius: 12, padding: 12, minWidth: 220 }}>
      <div style={{ fontWeight: 800, marginBottom: 6 }}>{title}</div>
      {lines.map((l, i) => (
        <div key={i} style={{ color: '#333' }}>
          {l}
        </div>
      ))}
    </div>
  );
}

export function SummaryCards(props: {
  referenceFirstOrange?: number | null;
  referenceFirstRed?: number | null;
  simulatedFirstOrange?: number | null;
  simulatedFirstRed?: number | null;
  mismatchCount?: number | null;
  hasReferenceOut?: boolean;
  hasSimulated: boolean;
}) {
  const { referenceFirstOrange, referenceFirstRed, simulatedFirstOrange, simulatedFirstRed, mismatchCount, hasReferenceOut, hasSimulated } = props;

  return (
    <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap', marginTop: 12 }}>
      <Card
        title="Reference"
        lines={[
          `first ORANGE: ${referenceFirstOrange == null ? '—' : `${round3(referenceFirstOrange)}s`}`,
          `first RED: ${referenceFirstRed == null ? '—' : `${round3(referenceFirstRed)}s`}`,
        ]}
      />
      {hasSimulated && (
        <Card
          title="Simulation"
          lines={[
            `first ORANGE: ${simulatedFirstOrange == null ? '—' : `${round3(simulatedFirstOrange)}s`}`,
            `first RED: ${simulatedFirstRed == null ? '—' : `${round3(simulatedFirstRed)}s`}`,
          ]}
        />
      )}
      {hasReferenceOut && (
        <Card
          title="Parity compare"
          lines={[
            `mismatches: ${mismatchCount == null ? '—' : mismatchCount}`,
            mismatchCount === 0 ? '✅ simulator default matches reference' : '⚠ see parity diffs in table',
          ]}
        />
      )}
    </div>
  );
}

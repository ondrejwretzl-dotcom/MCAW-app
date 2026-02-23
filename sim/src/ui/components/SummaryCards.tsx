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
  baseFirstOrange?: number | null;
  baseFirstRed?: number | null;
  tunedFirstOrange?: number | null;
  tunedFirstRed?: number | null;
  mismatchCount?: number | null;
  hasKotlinOut?: boolean;
  hasTuned: boolean;
}) {
  const { baseFirstOrange, baseFirstRed, tunedFirstOrange, tunedFirstRed, mismatchCount, hasKotlinOut, hasTuned } = props;

  return (
    <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap', marginTop: 12 }}>
      <Card
        title="Baseline"
        lines={[
          `first ORANGE: ${baseFirstOrange == null ? '—' : `${round3(baseFirstOrange)}s`}`,
          `first RED: ${baseFirstRed == null ? '—' : `${round3(baseFirstRed)}s`}`,
        ]}
      />
      {hasTuned && (
        <Card
          title="What-if"
          lines={[
            `first ORANGE: ${tunedFirstOrange == null ? '—' : `${round3(tunedFirstOrange)}s`}`,
            `first RED: ${tunedFirstRed == null ? '—' : `${round3(tunedFirstRed)}s`}`,
          ]}
        />
      )}
      {hasKotlinOut && (
        <Card
          title="Golden compare"
          lines={[
            `mismatches: ${mismatchCount == null ? '—' : mismatchCount}`,
            mismatchCount === 0 ? '✅ engine matches Kotlin output' : '⚠ see diffs / table',
          ]}
        />
      )}
    </div>
  );
}

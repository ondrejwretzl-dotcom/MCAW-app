import React from 'react';

export function InfoTip(props: { text: string }) {
  const { text } = props;
  return (
    <span
      style={{
        display: 'inline-flex',
        alignItems: 'center',
        justifyContent: 'center',
        width: 16,
        height: 16,
        borderRadius: 999,
        border: '1px solid #cbd5e1',
        color: '#334155',
        fontSize: 11,
        fontWeight: 700,
        lineHeight: '16px',
        cursor: 'help',
        userSelect: 'none',
      }}
      title={text}
      aria-label={text}
    >
      i
    </span>
  );
}

export function LabelWithInfo(props: { label: React.ReactNode; info?: string; right?: React.ReactNode }) {
  const { label, info, right } = props;
  return (
    <div style={{ display: 'flex', gap: 8, alignItems: 'center', justifyContent: 'space-between' }}>
      <div style={{ display: 'flex', gap: 6, alignItems: 'center', minWidth: 0 }}>
        <span style={{ fontSize: 12, color: '#111827', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{label}</span>
        {info ? <InfoTip text={info} /> : null}
      </div>
      {right ?? null}
    </div>
  );
}

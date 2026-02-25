import React from 'react';

export function LineChart({
  values,
  width = 640,
  height = 180,
  markers,
  yMin,
  yMax,
}: {
  values: Array<{ ts: number; v?: number }>;
  width?: number;
  height?: number;
  markers?: Array<{ ts: number; color: string; title: string }>;
  yMin?: number;
  yMax?: number;
}) {
  const clean = values.filter((x): x is { ts: number; v: number } => x.v != null && Number.isFinite(x.v));
  if (clean.length === 0) return <div className="empty">Bez dat pro graf.</div>;

  const minX = clean[0].ts;
  const maxX = clean[clean.length - 1].ts;
  const minY = yMin ?? Math.min(...clean.map((x) => x.v));
  const maxY = yMax ?? Math.max(...clean.map((x) => x.v));

  const path = clean
    .map((p, i) => {
      const x = ((p.ts - minX) / Math.max(1, maxX - minX)) * width;
      const y = height - ((p.v - minY) / Math.max(1e-6, maxY - minY)) * height;
      return `${i === 0 ? 'M' : 'L'} ${x} ${y}`;
    })
    .join(' ');

  return (
    <svg viewBox={`0 0 ${width} ${height}`} className="chart" preserveAspectRatio="none">
      <path d={path} className="riskPath" />
      {markers?.map((m, i) => {
        const x = ((m.ts - minX) / Math.max(1, maxX - minX)) * width;
        return <line key={`${m.ts}-${i}`} x1={x} x2={x} y1={0} y2={height} stroke={m.color} strokeWidth={1}><title>{m.title}</title></line>;
      })}
    </svg>
  );
}

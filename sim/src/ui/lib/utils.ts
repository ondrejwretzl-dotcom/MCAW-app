export function clamp(x: number, min: number, max: number): number {
  if (Number.isNaN(x)) return min;
  return Math.max(min, Math.min(max, x));
}

export function kmhToMps(kmh: number): number {
  return (kmh * 1000) / 3600;
}

export function mpsToKmh(mps: number): number {
  return (mps * 3600) / 1000;
}

export function round3(x: number): number {
  return Math.round(x * 1000) / 1000;
}

export function safeBaseName(name: string): string {
  return name.replace(/\.(frames\.)?jsonl$/i, '').replace(/\.md$/i, '');
}

export function downloadText(filename: string, content: string, mime = 'text/plain') {
  const blob = new Blob([content], { type: mime });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  a.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

export function toCsv(rows: Record<string, any>[]): string {
  if (rows.length === 0) return '';
  const cols = Object.keys(rows[0]);
  const esc = (v: any) => {
    if (v === null || v === undefined) return '';
    const s = String(v);
    if (/[",\n]/.test(s)) return `"${s.replace(/"/g, '""')}"`;
    return s;
  };
  return [cols.join(','), ...rows.map((r) => cols.map((c) => esc(r[c])).join(','))].join('\n');
}

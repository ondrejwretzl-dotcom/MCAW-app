import type { ParsedLogData } from '../types';

type SimFrame = {
  tMs: number;
  effectiveMode?: number | null;
  distanceM?: number | null;
  distanceConfidence?: number | null;
  approachSpeedMps?: number | null;
  ttcSec?: number | null;
  ttcSlopeSecPerSec?: number | null;
  roiContainment?: number | null;
  qualityWeight?: number | null;
  brakeCue?: number | null;
  brakeCueStrength?: number | null;
  egoBrake?: number | null;
  occlusionCloseFactor?: number | null;
  occlusionCloseEligible?: boolean | null;
  reasonBits?: number | null;
  riskScore?: number | null;
  level?: number | null;
  lockId?: number | null;
  finalTargetId?: number | null;
  source?: string | null;
};

function toFiniteOrNull(v: unknown): number | null {
  if (typeof v !== 'number') return null;
  return Number.isFinite(v) ? v : null;
}

function pickNumber(obj: any, ...keys: string[]): number | null {
  for (const k of keys) {
    const v = obj?.[k];
    if (typeof v === 'number' && Number.isFinite(v)) return v;
  }
  return null;
}

function pickBoolean(obj: any, ...keys: string[]): boolean | null {
  for (const k of keys) {
    const v = obj?.[k];
    if (typeof v === 'boolean') return v;
    if (typeof v === 'number') return v !== 0;
    if (typeof v === 'string') {
      const s = v.trim().toLowerCase();
      if (s === 'true' || s === '1') return true;
      if (s === 'false' || s === '0') return false;
    }
  }
  return null;
}

function downloadText(filename: string, content: string) {
  const blob = new Blob([content], { type: 'text/plain;charset=utf-8' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(url);
}

/**
 * Export loaded runtime logs to simulator-compatible JSONL.
 *
 * IMPORTANT: JSON must be strict. Do NOT emit NaN/Infinity tokens.
 * We sanitize all non-finite numbers to null before JSON.stringify.
 */
export function exportSimFramesJsonl(parsed: ParsedLogData, baseName = 'mcaw_export') {
  // Prefer metrics rows because they include distConf/approachSpeed and other fused signals.
  const rows: any[] = parsed.metricsRows && parsed.metricsRows.length > 0 ? parsed.metricsRows : parsed.riskRows;

  if (!rows || rows.length === 0) {
    downloadText(`${baseName}.export_report.md`, `No rows to export (no metrics/risk rows loaded).`);
    return;
  }

  const sorted = [...rows].sort((a, b) => (a.ts ?? 0) - (b.ts ?? 0));
  const t0 = sorted[0].ts ?? 0;

  const frames: SimFrame[] = [];
  const usedKeys = new Set<string>();

  for (const r of sorted) {
    const ts = typeof r.ts === 'number' ? r.ts : null;
    if (ts === null) continue;

    const extra = (r as any).extraFields;

    const frame: SimFrame = {
      tMs: Math.max(0, Math.round(ts - t0)),
      effectiveMode: toFiniteOrNull((r as any).mode ?? (extra?.mode != null ? Number(extra.mode) : undefined)),
      distanceM: pickNumber(r, 'distM', 'distInput', 'dist', 'distanceM'),
      distanceConfidence: pickNumber(r, 'distConf', 'distanceConfidence'),
      approachSpeedMps: pickNumber(r, 'approachSpeed', 'relV', 'relSignedEma'),
      ttcSec: pickNumber(r, 'ttc'),
      ttcSlopeSecPerSec: pickNumber(r, 'ttcSlopeSecPerSec'),
      roiContainment: pickNumber(r, 'roi'),
      qualityWeight: pickNumber(r, 'quality'),
      brakeCue: pickNumber(r, 'brake'),
      brakeCueStrength: pickNumber(r, 'brakeStrength', 'brakeCueStrength'),
      egoBrake: pickNumber(r, 'egoBrake'),
      occlusionCloseFactor: pickNumber(r, 'occlusionCloseFactor'),
      occlusionCloseEligible: pickBoolean(r, 'occlusionCloseEligible'),
      reasonBits: pickNumber(r, 'reasonBits', 'reasonPayload'),
      riskScore: pickNumber(r, 'riskScore', 'risk'),
      level: pickNumber(r, 'level'),
      lockId: pickNumber(r, 'lockId'),
      finalTargetId: pickNumber(r, 'finalTargetId'),
      source: typeof (r as any).source === 'string' ? (r as any).source : null,
    };

    if (Array.isArray((r as any).rawColumns)) {
      for (const c of (r as any).rawColumns) usedKeys.add(String(c));
    }

    // Normalize: if value is non-finite, export null.
    frame.distanceM = toFiniteOrNull(frame.distanceM);
    frame.distanceConfidence = toFiniteOrNull(frame.distanceConfidence);
    frame.approachSpeedMps = toFiniteOrNull(frame.approachSpeedMps);
    frame.ttcSec = toFiniteOrNull(frame.ttcSec);
    frame.ttcSlopeSecPerSec = toFiniteOrNull(frame.ttcSlopeSecPerSec);
    frame.roiContainment = toFiniteOrNull(frame.roiContainment);
    frame.qualityWeight = toFiniteOrNull(frame.qualityWeight);
    frame.brakeCue = toFiniteOrNull(frame.brakeCue);
    frame.brakeCueStrength = toFiniteOrNull(frame.brakeCueStrength);
    frame.egoBrake = toFiniteOrNull(frame.egoBrake);
    frame.occlusionCloseFactor = toFiniteOrNull(frame.occlusionCloseFactor);
    frame.reasonBits = toFiniteOrNull(frame.reasonBits);
    frame.riskScore = toFiniteOrNull(frame.riskScore);
    frame.level = toFiniteOrNull(frame.level);
    frame.lockId = toFiniteOrNull(frame.lockId);
    frame.finalTargetId = toFiniteOrNull(frame.finalTargetId);

    // Defaults (conservative).
    if (frame.distanceConfidence == null) frame.distanceConfidence = 1.0;
    if (frame.roiContainment == null) frame.roiContainment = 1.0;
    if (frame.qualityWeight == null) frame.qualityWeight = 1.0;
    if (frame.occlusionCloseFactor == null) frame.occlusionCloseFactor = 0.0;
    if (frame.occlusionCloseEligible == null) frame.occlusionCloseEligible = false;
    if (frame.ttcSlopeSecPerSec == null) frame.ttcSlopeSecPerSec = 0.0;

    frames.push(frame);
  }

  const meta = {
    type: 'META',
    schema: 'mcaw.frames.v1',
    source: 'log_analyzer',
    exportedAtUtc: new Date().toISOString(),
    rows: frames.length,
    hasMetrics: parsed.metricsRows.length > 0,
    hasRisk: parsed.riskRows.length > 0,
  };

  // JSON.stringify will turn NaN/Infinity into null, but we already sanitize explicitly.
  const jsonl = [JSON.stringify(meta), ...frames.map((f) => JSON.stringify(f))].join('\n') + '\n';
  downloadText(`${baseName}.frames.jsonl`, jsonl);

  // Minimal report: unknown columns.
  const known = new Set<string>(['ts']);
  const extras = [...usedKeys].filter((k) => !known.has(k)).sort();
  const report = [
    `# Export report`,
    ``,
    `rows_exported: ${frames.length}`,
    ``,
    `## Extra/unknown columns in source log`,
    extras.length ? extras.map((e) => `- ${e}`).join('\n') : `- (none)`,
    ``,
    `## Non-finite handling`,
    `- Any NaN/Infinity values are exported as null to keep JSON strict.`,
  ].join('\n');
  downloadText(`${baseName}.export_report.md`, report + '\n');
}

import { REL_INVALID_REASON_BITS } from '../parser/schema';
import type {
  FalseRedResult,
  MetricsRow,
  ParsedLogData,
  RelQualityResult,
  ScenarioTag,
  SplitMode,
  StandingSuppressorResult,
  SwitchBeneficialEvent,
  SwitchBeneficialResult,
  TtcMismatchResult,
} from '../types';

export function relQuality(data: ParsedLogData): RelQualityResult {
  const relevant = data.metricsRows.filter((m) => m.relDerivValid != null || m.relInvalidReasonMask != null);
  const invalid = relevant.filter((m) => (m.relDerivValid ?? 1) === 0);
  const byBitMap = new Map<number, number>();
  for (const row of invalid) {
    const mask = row.relInvalidReasonMask ?? 0;
    for (const b of REL_INVALID_REASON_BITS) {
      if ((mask & b.bit) !== 0) byBitMap.set(b.bit, (byBitMap.get(b.bit) ?? 0) + 1);
    }
  }
  return {
    totalRelevant: relevant.length,
    invalidCount: invalid.length,
    invalidRatio: relevant.length ? invalid.length / relevant.length : 0,
    byBit: [...byBitMap.entries()].map(([bit, count]) => ({ bit, count })),
  };
}

export function falseRedBottomTouch(data: ParsedLogData, splitMode: SplitMode): FalseRedResult {
  const formula = 'false-red := level=2 & bottomTouch=1 & !(ttc_decreasing || dist_decreasing) v následujících 2s';
  const sorted = [...data.metricsRows].sort((a, b) => a.ts - b.ts);

  const beforeRows = sorted.filter((m) => isBefore(m, splitMode));
  const afterRows = sorted.filter((m) => !isBefore(m, splitMode));

  return {
    before: evalSet(beforeRows),
    after: evalSet(afterRows),
    formula,
  };

  function evalSet(rows: MetricsRow[]) {
    const redBottom = rows.filter((r) => (r.level ?? 0) >= 2 && (r.bottomTouch ?? 0) === 1);
    let falseRed = 0;
    for (const r of redBottom) {
      const w = rows.filter((x) => x.ts > r.ts && x.ts <= r.ts + 2000);
      const ttcDec = hasDecreasing(w.map((x) => x.ttc).filter((x): x is number => x != null));
      const distDec = hasDecreasing(w.map((x) => x.distM).filter((x): x is number => x != null));
      if (!ttcDec && !distDec) falseRed += 1;
    }
    const rate = redBottom.length ? falseRed / redBottom.length : 0;
    return { redBottomTouch: redBottom.length, falseRed, rate };
  }
}

export function switchBeneficialRate(data: ParsedLogData): SwitchBeneficialResult {
  const rows = [...data.metricsRows].sort((a, b) => a.ts - b.ts);
  const switches = rows.filter((r) => (r.idSwitched ?? 0) === 1);
  const events: SwitchBeneficialEvent[] = switches.map((s) => {
    const before = rows.filter((x) => x.ts < s.ts && x.ts >= s.ts - 2000).map((x) => x.ttc).filter((x): x is number => x != null);
    const after = rows.filter((x) => x.ts > s.ts && x.ts <= s.ts + 2000).map((x) => x.ttc).filter((x): x is number => x != null);
    const beforeVar = variance(before);
    const afterVar = variance(after);
    return {
      ts: s.ts,
      lockId: s.lockId,
      beforeVar,
      afterVar,
      beneficial: afterVar < beforeVar,
    };
  });

  const beneficial = events.filter((e) => e.beneficial).length;
  return {
    total: events.length,
    beneficial,
    nonBeneficial: events.length - beneficial,
    rate: events.length ? beneficial / events.length : 0,
    events,
  };
}

export function standingSuppressorKpi(data: ParsedLogData, tags: ScenarioTag[]): StandingSuppressorResult {
  const nearStop = tags.filter((t) => t.kind === 'near_stop_critical');
  const missed = nearStop.filter((tag) => {
    const rows = data.riskRows.filter((r) => r.ts >= tag.tsStart && r.ts <= tag.tsEnd);
    const suppressed = rows.some((r) => ((r.reasonBits ?? 0) & (1 << 18)) !== 0);
    const noRed = rows.every((r) => (r.level ?? 0) < 2);
    return suppressed && noRed;
  });
  return {
    taggedNearStopCritical: nearStop.length,
    missedCriticalNearStop: missed.length,
    rate: nearStop.length ? missed.length / nearStop.length : 0,
    events: missed.map((m) => ({ tagId: m.id, tsStart: m.tsStart, tsEnd: m.tsEnd, note: m.note })),
  };
}

export function parseScenarioTags(text: string, sourceName: string): ScenarioTag[] {
  if (sourceName.endsWith('.json')) {
    const parsed = JSON.parse(text) as ScenarioTag[];
    return parsed;
  }

  const lines = text.split(/\r?\n/).filter(Boolean);
  const out: ScenarioTag[] = [];
  for (let i = 1; i < lines.length; i += 1) {
    const [id, tsStart, tsEnd, kind, note] = lines[i].split(',');
    out.push({
      id,
      tsStart: Number(tsStart),
      tsEnd: Number(tsEnd),
      kind: kind === 'near_stop_critical' ? 'near_stop_critical' : 'manual_note',
      note,
    });
  }
  return out;
}

function hasDecreasing(values: number[]): boolean {
  for (let i = 1; i < values.length; i += 1) if (values[i] < values[i - 1]) return true;
  return false;
}

function variance(values: number[]): number {
  if (values.length < 2) return 0;
  const mean = values.reduce((a, b) => a + b, 0) / values.length;
  return values.reduce((s, v) => s + (v - mean) ** 2, 0) / values.length;
}

function isBefore(row: MetricsRow, splitMode: SplitMode): boolean {
  if (splitMode.type === 'manual-ts') return row.ts < splitMode.splitTs;
  if (splitMode.type === 'manual-file') return row.source.startsWith(splitMode.filePrefix);
  return row.ts < inferAutoSplitTs(row.ts);
}

function inferAutoSplitTs(ts: number): number {
  // fallback heuristika: 2026-02-25 12:00:00 UTC
  const defaultSplit = Date.UTC(2026, 1, 25, 12, 0, 0);
  return defaultSplit;
}


export function ttcMismatchKpi(data: ParsedLogData): TtcMismatchResult {
  const rows = [...data.riskRows].sort((a, b) => a.ts - b.ts);
  const hits = rows.filter((r) => {
    const sanity = (r.ttcSanity ?? 0) >= 1;
    const d = r.ttcD;
    const t = r.ttc;
    const rel = r.relV ?? 0;
    const dist = r.dist ?? Number.POSITIVE_INFINITY;
    const mismatch = Number.isFinite(d) && Number.isFinite(t) && (d as number) < (t as number) * 0.7 && rel > 0.8 && dist < 25;
    return sanity || mismatch;
  });

  const windows: Array<{ tsStart: number; tsEnd: number; count: number; minRatio: number }> = [];
  const gapMs = 400;
  for (const r of hits) {
    const ratio = Number.isFinite(r.ttcMr) ? Number(r.ttcMr) : Number.POSITIVE_INFINITY;
    const last = windows[windows.length - 1];
    if (!last || r.ts - last.tsEnd > gapMs) {
      windows.push({ tsStart: r.ts, tsEnd: r.ts, count: 1, minRatio: ratio });
    } else {
      last.tsEnd = r.ts;
      last.count += 1;
      last.minRatio = Math.min(last.minRatio, ratio);
    }
  }

  windows.sort((a, b) => a.minRatio - b.minRatio || b.count - a.count);
  return { events: hits.length, windows: windows.slice(0, 10) };
}

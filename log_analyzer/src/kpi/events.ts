import type { EventGroup, MetricsRow, ParsedLogData, RiskRow, Severity, ServiceRow } from '../types';

export function buildEventGroups(data: ParsedLogData): EventGroup[] {
  const ts = [
    ...data.riskRows.map((r) => r.ts),
    ...data.metricsRows.map((m) => m.ts),
    ...data.serviceRows.map((s) => s.ts),
  ].sort((a, b) => a - b);

  if (!ts.length) return [];

  const ranges: Array<{ s: number; e: number }> = [];
  let s = ts[0];
  let e = ts[0];
  for (let i = 1; i < ts.length; i += 1) {
    if (ts[i] - e <= 350) e = ts[i];
    else {
      ranges.push({ s, e });
      s = ts[i];
      e = ts[i];
    }
  }
  ranges.push({ s, e });

  return ranges.map((r) => {
    const riskRows = data.riskRows.filter((x) => x.ts >= r.s && x.ts <= r.e);
    const metricsRows = data.metricsRows.filter((x) => x.ts >= r.s && x.ts <= r.e);
    const serviceRows = data.serviceRows.filter((x) => x.ts >= r.s && x.ts <= r.e);
    const severity = deriveSeverity(riskRows);
    return {
      id: `${r.s}-${r.e}`,
      tsStart: r.s,
      tsEnd: r.e,
      severity,
      summary:
        severity === 'critical'
          ? 'Kritická epizoda (RED nebo vysoké risk score).'
          : severity === 'watch'
            ? 'Pozorovaná epizoda (ORANGE / signály ke kontrole).'
            : 'Informační epizoda.',
      recommendation:
        severity === 'critical'
          ? 'Priorita: zkontrolujte TTC, DIST, REL trend a reason bits.'
          : severity === 'watch'
            ? 'Zkontrolujte vývoj risk score a případné přepnutí targetu.'
            : 'Použijte jako kontext.',
      riskRows,
      metricsRows,
      serviceRows,
      sources: [...new Set([...riskRows.map((x) => x.source), ...metricsRows.map((x) => x.source), ...serviceRows.map((x) => x.file)])],
    };
  });
}

function deriveSeverity(riskRows: RiskRow[]): Severity {
  const maxLevel = Math.max(0, ...riskRows.map((r) => r.level ?? 0));
  const maxRisk = Math.max(0, ...riskRows.map((r) => r.risk ?? 0));
  if (maxLevel >= 2 || maxRisk >= 0.75) return 'critical';
  if (maxLevel >= 1 || maxRisk >= 0.45) return 'watch';
  return 'info';
}

export function joinRiskMetrics(data: ParsedLogData): Array<{ risk?: RiskRow; metrics?: MetricsRow; service?: ServiceRow; ts: number }> {
  const all = [
    ...data.riskRows.map((r) => ({ ts: r.ts, risk: r })),
    ...data.metricsRows.map((m) => ({ ts: m.ts, metrics: m })),
    ...data.serviceRows.map((s) => ({ ts: s.ts, service: s })),
  ].sort((a, b) => a.ts - b.ts);
  return all;
}

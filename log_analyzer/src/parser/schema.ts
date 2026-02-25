import type { ParsedLogData } from '../types';

export function summarizeSchema(data: ParsedLogData): string[] {
  const notices: string[] = [];
  if (!data.schemaSummary.hasRisk) notices.push('Soubor neobsahuje standardní risk řádky. Zobrazuji alespoň service/METRICS data.');
  if (!data.schemaSummary.hasMetrics) notices.push('Soubor neobsahuje METRICS řádky – některé KPI budou neúplné.');
  if (data.schemaSummary.unknownCount > 0) notices.push(`Nalezeno ${data.schemaSummary.unknownCount} neznámých řádků/struktur.`);
  if (data.dataQuality.partialRows > 0) notices.push(`Částečně parsovaných řádků: ${data.dataQuality.partialRows}.`);
  return notices;
}

export const REL_INVALID_REASON_BITS: Array<{ bit: number; text: string }> = [
  { bit: 1 << 0, text: 'insufficient history' },
  { bit: 1 << 1, text: 'noisy derivative' },
  { bit: 1 << 2, text: 'switch instability' },
  { bit: 1 << 3, text: 'ttc unavailable' },
  { bit: 1 << 4, text: 'distance outlier' },
  { bit: 1 << 5, text: 'sensor mismatch' },
];

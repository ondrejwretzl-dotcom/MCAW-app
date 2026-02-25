import { parseCsvLine, toNum } from './csv';
import type { MetricsRow, ParsedLogData, RiskRow, ServiceRow, UnknownRow } from '../types';

const RISK_HEADER = ['ts_ms', 'risk', 'level', 'state', 'reason_bits'];

type LoadedFile = { fileName: string; text: string };

export async function loadFiles(files: File[]): Promise<LoadedFile[]> {
  return Promise.all(files.map(async (f) => ({ fileName: f.name, text: await f.text() })));
}

export function parseLoadedLogs(loaded: LoadedFile[]): ParsedLogData {
  const serviceRows: ServiceRow[] = [];
  const riskRows: RiskRow[] = [];
  const metricsRows: MetricsRow[] = [];
  const unknownRows: UnknownRow[] = [];
  const warnings: string[] = [];
  const rejectedReasons: Record<string, number> = {};
  let validRows = 0;
  let partialRows = 0;
  let rejectedRows = 0;

  for (const file of loaded) {
    const lines = file.text.split(/\r?\n/).filter(Boolean);
    let riskHeaderSeen = false;

    for (const line of lines) {
      const cols = parseCsvLine(line);
      if (cols.length === 0) continue;

      if (cols[0] === 'ts_ms') {
        riskHeaderSeen = RISK_HEADER.every((key, i) => (cols[i] || '').trim() === key);
        if (!riskHeaderSeen) {
          warnings.push(`Soubor ${file.fileName}: risk header se liší od očekávaného kontraktu.`);
        }
        continue;
      }

      if (cols[0] === 'S') {
        const ts = toNum(cols[1]);
        if (ts == null) {
          addRejected('invalid_service_ts');
          continue;
        }
        serviceRows.push({ type: 'service', ts, message: cols.slice(2).join(','), file: file.fileName });
        validRows += 1;
        continue;
      }

      if (cols[0] === 'M' && cols[2] === 'METRICS') {
        const ts = toNum(cols[1]);
        if (ts == null) {
          addRejected('invalid_metrics_ts');
          continue;
        }
        const extraFields = buildExtra(cols, {
          lockId: 3,
          consecutiveDetections: 4,
          roiBottomPx: 7,
          boxBottomPx: 8,
          bottomTouch: 9,
          idSwitched: 10,
          relDerivValid: 11,
          relInvalidReasonMask: 12,
          distInputRaw: 17,
          distInput: 18,
          distM: 19,
          relSignedEma: 20,
          approachSpeed: 21,
          ttcFromDist: 22,
          ttc: 23,
          riskScore: 24,
          level: 25,
          reasonPayload: 26,
          reasonId: 27,
          finalTargetId: 28,
        });
        metricsRows.push({
          type: 'metrics',
          ts,
          lockId: toNum(cols[3]),
          consecutiveDetections: toNum(cols[4]),
          roiBottomPx: toNum(cols[7]),
          boxBottomPx: toNum(cols[8]),
          bottomTouch: toNum(cols[9]),
          idSwitched: toNum(cols[10]),
          relDerivValid: toNum(cols[11]),
          relInvalidReasonMask: toNum(cols[12]),
          distInputRaw: toNum(cols[17]),
          distInput: toNum(cols[18]),
          distM: toNum(cols[19]),
          relSignedEma: toNum(cols[20]),
          approachSpeed: toNum(cols[21]),
          ttcFromDist: toNum(cols[22]),
          ttc: toNum(cols[23]),
          riskScore: toNum(cols[24]),
          level: toNum(cols[25]),
          reasonPayload: toNum(cols[26]),
          reasonId: toNum(cols[27]),
          finalTargetId: toNum(cols[28]),
          rawColumns: cols,
          extraFields,
          source: file.fileName,
        });
        validRows += 1;
        if (Object.keys(extraFields).length > 0) partialRows += 1;
        continue;
      }

      const ts = toNum(cols[0]);
      const mayBeRisk = ts != null && cols.length >= 4;
      if (mayBeRisk && cols[1] !== 'TRACK') {
        const extraFields = buildExtra(cols, {
          risk: 1,
          level: 2,
          state: 3,
          reasonBits: 4,
          ttc: 5,
          dist: 6,
          relV: 7,
          roi: 8,
          quality: 9,
          cutIn: 10,
          brake: 11,
          egoBrake: 12,
          mode: 13,
          lockId: 14,
          label: 15,
          detScore: 16,
          reasonId: 17,
          finalTargetId: 18,
        });
        const risk = toNum(cols[1]);
        const row: RiskRow = {
          type: 'risk',
          ts,
          risk,
          level: toNum(cols[2]),
          state: cols[3],
          reasonBits: toNum(cols[4]),
          ttc: toNum(cols[5]),
          dist: toNum(cols[6]),
          relV: toNum(cols[7]),
          roi: toNum(cols[8]),
          quality: toNum(cols[9]),
          cutIn: toNum(cols[10]),
          brake: toNum(cols[11]),
          egoBrake: toNum(cols[12]),
          mode: toNum(cols[13]),
          lockId: toNum(cols[14]),
          label: cols[15],
          detScore: toNum(cols[16]),
          reasonId: toNum(cols[17]),
          finalTargetId: toNum(cols[18]),
          rawColumns: cols,
          extraFields,
          source: file.fileName,
        };
        riskRows.push(row);
        if (risk == null || row.level == null) partialRows += 1;
        else validRows += 1;
        if (Object.keys(extraFields).length > 0) partialRows += 1;
        continue;
      }

      addRejected(riskHeaderSeen ? 'unknown_row' : 'unknown_row_or_schema');
      unknownRows.push({
        type: 'unknown',
        ts,
        source: file.fileName,
        raw: line,
        columns: cols,
        reason: 'Nepodporovaná nebo neznámá struktura řádku',
      });
    }
  }

  const schemaSummary = {
    hasService: serviceRows.length > 0,
    hasRisk: riskRows.length > 0,
    hasMetrics: metricsRows.length > 0,
    unknownCount: unknownRows.length,
  };

  return {
    serviceRows,
    riskRows,
    metricsRows,
    unknownRows,
    warnings,
    schemaSummary,
    dataQuality: {
      validRows,
      partialRows,
      rejectedRows,
      rejectedReasons,
    },
  };

  function addRejected(reason: string) {
    rejectedRows += 1;
    rejectedReasons[reason] = (rejectedReasons[reason] ?? 0) + 1;
  }
}

function buildExtra(cols: string[], known: Record<string, number>): Record<string, string> {
  const knownIndices = new Set(Object.values(known));
  const extra: Record<string, string> = {};
  cols.forEach((value, idx) => {
    if (!knownIndices.has(idx) && value !== '') extra[`col_${idx}`] = value;
  });
  return extra;
}

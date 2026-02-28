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
  let hasRiderSpeedContract = false;

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
        if (cols.includes("rider_speed_mps") && cols.includes("rider_speed_method")) hasRiderSpeedContract = true;
        continue;
      }

      if (cols[0] === 'S') {
        const ts = toNum(cols[1]);
        if (ts == null) {
          addRejected('invalid_service_ts');
          continue;
        }
        serviceRows.push({ type: 'service', ts, message: cols.slice(2).join(','), file: file.fileName });
        if (toNum(cols[30]) != null || toNum(cols[34]) != null) hasRiderSpeedContract = true;
        validRows += 1;
        continue;
      }

      if (cols[0] === 'M' && (cols[2] === 'METRICS' || cols[2] === 'METRICS2')) {
        const ts = toNum(cols[1]);
        if (ts == null) {
          addRejected('invalid_metrics_ts');
          continue;
        }
        const isV2 = cols[2] === 'METRICS2';
        const idx = isV2
          ? {
              lockId: 3, consecutiveDetections: 4, roiBottomPx: 7, boxBottomPx: 8, bottomTouch: 10,
              idSwitched: 11, relDerivValid: 12, relInvalidReasonMask: 13, distInputRaw: 18,
              distInput: 19, distM: 20, distSlopeEma: 21, relSignedEma: 22, relAbsEma: 23,
              approachSpeed: 24, ttcFromDist: 25, ttc: 26, riskScore: 27, level: 28,
              reasonPayload: 29, reasonId: 30, trendState: 31, steadyMs: 32, approachMs: 33,
              steadySuppressActive: 34, reenterCooldownMs: 35, distSource: 36, distConf: 37,
              riderSpeedRawMps: 38, riderSpeedMps: 39, riderSpeedConfidence: 40, riderSpeedSource: 41,
              riderSpeedAgeMs: 42, riderSpeedMethod: 43, ttcH: 44, ttcD: 45, ttcWd: 46, ttcMr: 47, ttcSanity: 48,
            }
          : {
              lockId: 3, consecutiveDetections: 4, roiBottomPx: 7, boxBottomPx: 8, bottomTouch: 9,
              idSwitched: 10, relDerivValid: 11, relInvalidReasonMask: 12, distInputRaw: 17,
              distInput: 18, distM: 19, relSignedEma: 20, approachSpeed: 21, ttcFromDist: 22,
              ttc: 23, riskScore: 24, level: 25, reasonPayload: 26, reasonId: 27, riderSpeedRawMps: 29,
              riderSpeedMps: 30, riderSpeedConfidence: 31, riderSpeedSource: 32, riderSpeedAgeMs: 33,
              riderSpeedMethod: 34,
            };
        const extraFields = buildExtra(cols, idx as Record<string, number>);
        metricsRows.push({
          type: 'metrics', ts,
          lockId: toNum(cols[idx.lockId]),
          consecutiveDetections: toNum(cols[idx.consecutiveDetections]),
          roiBottomPx: toNum(cols[idx.roiBottomPx]),
          boxBottomPx: toNum(cols[idx.boxBottomPx]),
          bottomTouch: toNum(cols[idx.bottomTouch]),
          idSwitched: toNum(cols[idx.idSwitched]),
          relDerivValid: toNum(cols[idx.relDerivValid]),
          relInvalidReasonMask: toNum(cols[idx.relInvalidReasonMask]),
          distInputRaw: toNum(cols[idx.distInputRaw]),
          distInput: toNum(cols[idx.distInput]),
          distM: toNum(cols[idx.distM]),
          relSignedEma: toNum(cols[idx.relSignedEma]),
          relAbsEma: isV2 ? toNum(cols[idx.relAbsEma]) : undefined,
          distSlopeEma: isV2 ? toNum(cols[idx.distSlopeEma]) : undefined,
          approachSpeed: toNum(cols[idx.approachSpeed]),
          ttcFromDist: toNum(cols[idx.ttcFromDist]),
          ttc: toNum(cols[idx.ttc]),
          riskScore: toNum(cols[idx.riskScore]),
          level: toNum(cols[idx.level]),
          reasonPayload: toNum(cols[idx.reasonPayload]),
          reasonId: toNum(cols[idx.reasonId]),
          trendState: isV2 ? toNum(cols[idx.trendState]) : undefined,
          steadyMs: isV2 ? toNum(cols[idx.steadyMs]) : undefined,
          approachMs: isV2 ? toNum(cols[idx.approachMs]) : undefined,
          steadySuppressActive: isV2 ? toNum(cols[idx.steadySuppressActive]) : undefined,
          reenterCooldownMs: isV2 ? toNum(cols[idx.reenterCooldownMs]) : undefined,
          distSource: isV2 ? toNum(cols[idx.distSource]) : undefined,
          distConf: isV2 ? toNum(cols[idx.distConf]) : undefined,
          riderSpeedRawMps: toNum(cols[idx.riderSpeedRawMps]),
          riderSpeedMps: toNum(cols[idx.riderSpeedMps]),
          riderSpeedConfidence: toNum(cols[idx.riderSpeedConfidence]),
          riderSpeedSource: toNum(cols[idx.riderSpeedSource]),
          riderSpeedAgeMs: toNum(cols[idx.riderSpeedAgeMs]),
          riderSpeedMethod: toNum(cols[idx.riderSpeedMethod]),
          ttcH: isV2 ? toNum(cols[idx.ttcH]) : undefined,
          ttcD: isV2 ? toNum(cols[idx.ttcD]) : undefined,
          ttcWd: isV2 ? toNum(cols[idx.ttcWd]) : undefined,
          ttcMr: isV2 ? toNum(cols[idx.ttcMr]) : undefined,
          ttcSanity: isV2 ? toNum(cols[idx.ttcSanity]) : undefined,
          rawColumns: cols, extraFields, source: file.fileName,
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
          riderSpeedMps: 19,
          riderSpeedConfidence: 20,
          riderSpeedSource: 21,
          riderSpeedAgeMs: 22,
          riderSpeedMethod: 23,
          ttcH: 24,
          ttcD: 25,
          ttcWd: 26,
          ttcMr: 27,
          ttcSanity: 28,
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
          riderSpeedMps: toNum(cols[19]),
          riderSpeedConfidence: toNum(cols[20]),
          riderSpeedSource: toNum(cols[21]),
          riderSpeedAgeMs: toNum(cols[22]),
          riderSpeedMethod: toNum(cols[23]),
          ttcH: toNum(cols[24]),
          ttcD: toNum(cols[25]),
          ttcWd: toNum(cols[26]),
          ttcMr: toNum(cols[27]),
          ttcSanity: toNum(cols[28]),
          rawColumns: cols,
          extraFields,
          source: file.fileName,
        };
        if (row.riderSpeedMps != null || row.riderSpeedMethod != null) hasRiderSpeedContract = true;
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
    hasRiderSpeedContract,
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

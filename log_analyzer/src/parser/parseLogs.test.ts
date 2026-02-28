import { describe, expect, it } from 'vitest';
import { parseLoadedLogs } from './parseLogs';
import { SAMPLE_LOG } from '../test-fixtures/sampleLog';

describe('parseLoadedLogs', () => {
  it('parses known structures and keeps unknown rows', () => {
    const data = parseLoadedLogs([{ fileName: 'any.log', text: SAMPLE_LOG }]);
    expect(data.serviceRows.length).toBe(1);
    expect(data.riskRows.length).toBe(1);
    expect(data.metricsRows.length).toBe(2);
    expect(data.unknownRows.length).toBeGreaterThanOrEqual(1);
    expect(data.schemaSummary.hasRisk).toBe(true);
    expect(data.schemaSummary.hasMetrics).toBe(true);
  });

  it('parses METRICS2 row with exact field mapping', () => {
    const cols = [
      'M', '1000', 'METRICS2',
      '7', '3', '1.0', '400', '390', '388', '12', '1', '0', '1', '5',
      '10.0', '9.8', '9.7', '9.6', '9.6', '9.5', '9.4', '-0.05', '0.30', '0.30', '0.30',
      '31.2', '31.0', '0.500', '0', '0', '0', '0', '1300', '0', '1', '0', '4', '0.90',
      '8.0', '8.0', '1.0', '1', '20', '1', '9.5', '3.8', '0.55', '0.40', '1'
    ];
    const text = cols.join(',');
    const data = parseLoadedLogs([{ fileName: 'm2.log', text }]);
    expect(data.metricsRows.length).toBe(1);
    const m = data.metricsRows[0];

    expect(m.bottomTouch).toBe(1);
    expect(m.idSwitched).toBe(0);
    expect(m.relDerivValid).toBe(1);
    expect(m.relInvalidReasonMask).toBe(5);
    expect(m.trendState).toBe(0);
    expect(m.steadySuppressActive).toBe(1);
    expect(m.relSignedEma).toBeCloseTo(0.3);
    expect(m.ttcH).toBeCloseTo(9.5);
    expect(m.ttcD).toBeCloseTo(3.8);
    expect(m.ttcSanity).toBe(1);
  });
});

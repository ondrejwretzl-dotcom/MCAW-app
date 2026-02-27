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

  it('parses METRICS2 row with trend/steady fields', () => {
    const text = 'M,1000,METRICS2,7,3,1.0,400,390,388,12,0,0,1,0,10.0,9.8,9.7,9.6,9.6,9.5,9.4,-0.05,0.30,0.30,0.30,31.2,31.0,0.500,0,0,0,1300,0,1,0,4,0.90,8.0,8.0,1.0,1,20,1';
    const data = parseLoadedLogs([{ fileName: 'm2.log', text }]);
    expect(data.metricsRows.length).toBe(1);
    const m = data.metricsRows[0];
    expect(m.trendState).not.toBeUndefined();
    expect(m.steadyMs).not.toBeUndefined();
    expect(m.steadySuppressActive).not.toBeUndefined();
    expect(m.distSource).not.toBeUndefined();
    expect(m.relSignedEma).toBeCloseTo(0.3);
  });

});

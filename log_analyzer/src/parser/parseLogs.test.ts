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
});

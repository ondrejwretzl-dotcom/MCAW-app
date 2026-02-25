import { describe, expect, it } from 'vitest';
import { parseLoadedLogs } from '../parser/parseLogs';
import { SAMPLE_LOG } from '../test-fixtures/sampleLog';
import { falseRedBottomTouch, relQuality, switchBeneficialRate } from './metrics';

describe('kpi metrics', () => {
  const data = parseLoadedLogs([{ fileName: 'sample.log', text: SAMPLE_LOG }]);

  it('computes rel quality', () => {
    const rel = relQuality(data);
    expect(rel.totalRelevant).toBeGreaterThan(0);
  });

  it('computes false red structure', () => {
    const result = falseRedBottomTouch(data, { type: 'auto', label: 'auto' });
    expect(result.before.redBottomTouch + result.after.redBottomTouch).toBeGreaterThanOrEqual(0);
  });

  it('computes switch beneficial', () => {
    const sw = switchBeneficialRate(data);
    expect(sw.total).toBeGreaterThanOrEqual(0);
  });
});

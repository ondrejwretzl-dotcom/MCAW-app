import type { ObjectSegment, ParsedLogData, SegmentPoint } from '../types';

export function buildObjectSegments(data: ParsedLogData): ObjectSegment[] {
  const points = mergePoints(data);
  const lockSegs = createSegments(points, 'lock');
  const finalSegs = createSegments(points, 'final-target');
  return [...lockSegs, ...finalSegs].sort((a, b) => a.tsStart - b.tsStart);
}

function mergePoints(data: ParsedLogData): SegmentPoint[] {
  const byTs = new Map<number, SegmentPoint>();
  for (const r of data.riskRows) {
    const p = byTs.get(r.ts) ?? { ts: r.ts };
    p.lockId = r.lockId;
    p.finalTargetId = r.finalTargetId;
    p.ttc = r.ttc;
    p.rel = r.relV;
    p.dist = r.dist;
    p.risk = r.risk;
    p.level = r.level;
    p.reasonBits = r.reasonBits;
    p.suppressStanding = ((r.reasonBits ?? 0) & (1 << 18)) !== 0;
    byTs.set(r.ts, p);
  }
  for (const m of data.metricsRows) {
    const p = byTs.get(m.ts) ?? { ts: m.ts };
    p.lockId = m.lockId ?? p.lockId;
    p.finalTargetId = m.finalTargetId ?? p.finalTargetId;
    p.ttc = m.ttc ?? p.ttc;
    p.rel = m.relSignedEma ?? p.rel;
    p.dist = m.distM ?? p.dist;
    p.approachSpeed = m.approachSpeed;
    p.bottomTouch = m.bottomTouch;
    p.switched = (m.idSwitched ?? 0) === 1;
    byTs.set(m.ts, p);
  }
  return [...byTs.values()].sort((a, b) => a.ts - b.ts);
}

function createSegments(points: SegmentPoint[], kind: 'lock' | 'final-target'): ObjectSegment[] {
  const out: ObjectSegment[] = [];
  let current: SegmentPoint[] = [];
  let currentId: number | undefined;

  for (const p of points) {
    const id = kind === 'lock' ? p.lockId : p.finalTargetId;
    if (id == null) continue;
    if (currentId == null) {
      currentId = id;
      current = [p];
      continue;
    }
    if (id === currentId && p.ts - current[current.length - 1].ts <= 1500) {
      current.push(p);
      continue;
    }
    out.push(toSegment(kind, currentId, current));
    currentId = id;
    current = [p];
  }

  if (currentId != null && current.length) out.push(toSegment(kind, currentId, current));
  return out;
}

function toSegment(kind: 'lock' | 'final-target', objectId: number, points: SegmentPoint[]): ObjectSegment {
  return {
    id: `${kind}-${objectId}-${points[0].ts}`,
    kind,
    objectId,
    tsStart: points[0].ts,
    tsEnd: points[points.length - 1].ts,
    points,
  };
}

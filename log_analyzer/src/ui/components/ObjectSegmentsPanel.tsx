import React, { useMemo, useState } from 'react';
import type { ObjectSegment } from '../../types';
import { LineChart } from './LineChart';

export function ObjectSegmentsPanel({ segments }: { segments: ObjectSegment[] }) {
  const [selected, setSelected] = useState<string>('');
  const visible = useMemo(() => segments.slice(0, 120), [segments]);
  const active = visible.find((s) => s.id === selected) ?? visible[0];

  return (
    <section className="card">
      <h2>Object Segments</h2>
      <div className="grid">
        <div className="timeline">
          {visible.map((s) => (
            <button key={s.id} className={`timelineItem ${active?.id === s.id ? 'active' : ''}`} onClick={() => setSelected(s.id)}>
              <b>{s.kind}</b> objectId={s.objectId} [{new Date(s.tsStart).toLocaleTimeString('cs-CZ')} - {new Date(s.tsEnd).toLocaleTimeString('cs-CZ')}]
            </button>
          ))}
        </div>
        <div>
          {!active && <div className="empty">Žádné segmenty.</div>}
          {active && (
            <>
              <h3>Segment detail: {active.kind} / {active.objectId}</h3>
              <LineChart values={active.points.map((p) => ({ ts: p.ts, v: p.ttc }))} markers={markers(active)} />
              <LineChart values={active.points.map((p) => ({ ts: p.ts, v: p.rel }))} markers={markers(active)} />
              <LineChart values={active.points.map((p) => ({ ts: p.ts, v: p.dist }))} markers={markers(active)} />
              <LineChart values={active.points.map((p) => ({ ts: p.ts, v: p.approachSpeed }))} markers={markers(active)} />

              <div className="tableWrap">
                <table>
                  <thead>
                    <tr>
                      <th>ts</th><th>lock</th><th>final target</th><th>risk</th><th>level</th><th>ttc</th><th>dist</th><th>rel</th><th>reasonBits</th>
                    </tr>
                  </thead>
                  <tbody>
                    {active.points.map((p) => (
                      <tr key={p.ts}>
                        <td>{p.ts}</td><td>{p.lockId ?? '-'}</td><td>{p.finalTargetId ?? '-'}</td><td>{p.risk ?? '-'}</td><td>{p.level ?? '-'}</td><td>{p.ttc ?? '-'}</td><td>{p.dist ?? '-'}</td><td>{p.rel ?? '-'}</td><td>{p.reasonBits ?? '-'}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </>
          )}
        </div>
      </div>
    </section>
  );
}

function markers(segment: ObjectSegment): Array<{ ts: number; color: string; title: string }> {
  const out: Array<{ ts: number; color: string; title: string }> = [];
  for (const p of segment.points) {
    if ((p.level ?? 0) >= 2) out.push({ ts: p.ts, color: '#ff5555', title: 'RED' });
    else if ((p.level ?? 0) === 1) out.push({ ts: p.ts, color: '#ffb347', title: 'ORANGE' });
    if (p.switched) out.push({ ts: p.ts, color: '#9f79ff', title: 'switch' });
    if ((p.bottomTouch ?? 0) === 1) out.push({ ts: p.ts, color: '#00e1ff', title: 'bottom touch' });
    if (p.suppressStanding) out.push({ ts: p.ts, color: '#95ff66', title: 'standing suppress' });
  }
  return out;
}

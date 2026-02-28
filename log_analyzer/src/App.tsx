import { useEffect, useMemo, useState } from 'react';
import { summarizeSchema } from './parser/schema';
import { loadFiles, parseLoadedLogs } from './parser/parseLogs';
import type { EventGroup, ParsedLogData, ScenarioTag, Severity, SplitMode } from './types';
import { buildEventGroups } from './kpi/events';
import { falseRedBottomTouch, parseScenarioTags, relQuality, standingSuppressorKpi, switchBeneficialRate, ttcMismatchKpi } from './kpi/metrics';
import { buildObjectSegments } from './kpi/segments';
import { DataQualityPanel } from './ui/components/DataQualityPanel';
import { KpiDashboard } from './ui/components/KpiDashboard';
import { LineChart } from './ui/components/LineChart';
import { ObjectSegmentsPanel } from './ui/components/ObjectSegmentsPanel';

const REASON_BITS: Array<{ bit: number; key: string; text: string }> = [
  { bit: 1 << 0, key: 'TTC', text: 'Kritické Time-To-Collision.' },
  { bit: 1 << 1, key: 'DIST', text: 'Nebezpečně krátká vzdálenost.' },
  { bit: 1 << 2, key: 'REL', text: 'Vysoká přibližovací rychlost.' },
  { bit: 1 << 5, key: 'CUT_IN', text: 'Objekt se zařezává.' },
  { bit: 1 << 14, key: 'DIST_DYN', text: 'Dynamické distance prahy.' },
  { bit: 1 << 18, key: 'SUPPRESS_STANDING', text: 'Potlačeno jako standing objekt.' },
];

export function App() {
  const [parsed, setParsed] = useState<ParsedLogData | undefined>();
  const [groups, setGroups] = useState<EventGroup[]>([]);
  const [selectedId, setSelectedId] = useState<string>('');
  const [detailOpen, setDetailOpen] = useState(false);
  const [severityFilter, setSeverityFilter] = useState<'all' | Severity>('all');
  const [splitMode, setSplitMode] = useState<SplitMode>({ type: 'auto', label: 'Auto #74 split' });
  const [tags, setTags] = useState<ScenarioTag[]>([]);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setDetailOpen(false);
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, []);

  const notices = useMemo(() => (parsed ? summarizeSchema(parsed) : []), [parsed]);
  const visibleGroups = useMemo(
    () => groups.filter((g) => (severityFilter === 'all' ? true : g.severity === severityFilter)),
    [groups, severityFilter]
  );
  const selectedGroup = useMemo(() => groups.find((g) => g.id === selectedId), [groups, selectedId]);

  const rel = useMemo(() => (parsed ? relQuality(parsed) : { totalRelevant: 0, invalidCount: 0, invalidRatio: 0, byBit: [] }), [parsed]);
  const falseRed = useMemo(
    () => (parsed ? falseRedBottomTouch(parsed, splitMode) : { before: { redBottomTouch: 0, falseRed: 0, rate: 0 }, after: { redBottomTouch: 0, falseRed: 0, rate: 0 }, formula: '' }),
    [parsed, splitMode]
  );
  const switches = useMemo(() => (parsed ? switchBeneficialRate(parsed) : { total: 0, beneficial: 0, nonBeneficial: 0, rate: 0, events: [] }), [parsed]);
  const standing = useMemo(() => (parsed ? standingSuppressorKpi(parsed, tags) : { taggedNearStopCritical: 0, missedCriticalNearStop: 0, rate: 0, events: [] }), [parsed, tags]);
  const ttcMismatch = useMemo(() => (parsed ? ttcMismatchKpi(parsed) : { events: 0, windows: [] }), [parsed]);
  const segments = useMemo(() => (parsed ? buildObjectSegments(parsed) : []), [parsed]);

  return (
    <div className="page">
      <header>
        <h1>MCAW log_analyzer</h1>
        <p>Robustní analyzér logů (různé struktury), KPI dashboard a segmentová analytika objectId.</p>
      </header>

      <section className="card controls">
        <h2>Load log files</h2>
        <input
          type="file"
          multiple
          accept=".txt,.csv,.log"
          onChange={async (ev) => {
            const files = Array.from(ev.target.files ?? []);
            const loaded = await loadFiles(files);
            const data = parseLoadedLogs(loaded);
            setParsed(data);
            const built = buildEventGroups(data);
            setGroups(built);
            setSelectedId(built[0]?.id ?? '');
            setDetailOpen(false);
          }}
        />

        <div style={{ marginTop: 8 }}>
          <label>Split pro "before/after #74": </label>
          <select
            value={splitMode.type}
            onChange={(e) => {
              const t = e.target.value;
              if (t === 'manual-ts') setSplitMode({ type: 'manual-ts', label: 'Manual timestamp split', splitTs: Date.now() });
              else if (t === 'manual-file') setSplitMode({ type: 'manual-file', label: 'Manual file split', filePrefix: '' });
              else setSplitMode({ type: 'auto', label: 'Auto #74 split' });
            }}
          >
            <option value="auto">Auto</option>
            <option value="manual-ts">Manual timestamp</option>
            <option value="manual-file">Manual file prefix</option>
          </select>
          {splitMode.type === 'manual-ts' && (
            <input
              type="number"
              value={splitMode.splitTs}
              onChange={(e) => setSplitMode({ ...splitMode, splitTs: Number(e.target.value) })}
            />
          )}
          {splitMode.type === 'manual-file' && (
            <input
              type="text"
              value={splitMode.filePrefix}
              placeholder="file prefix"
              onChange={(e) => setSplitMode({ ...splitMode, filePrefix: e.target.value })}
            />
          )}
        </div>

        <div style={{ marginTop: 8 }}>
          <label>Import manuálních tagů scén (JSON/CSV): </label>
          <input
            type="file"
            accept=".json,.csv"
            onChange={async (ev) => {
              const file = ev.target.files?.[0];
              if (!file) return;
              const txt = await file.text();
              setTags(parseScenarioTags(txt, file.name));
            }}
          />
        </div>

        <div className="chips">
          {parsed?.warnings.map((w) => <span key={w} className="chip err">{w}</span>)}
          {notices.map((n) => <span key={n} className="chip mute">{n}</span>)}
        </div>
      </section>

      <DataQualityPanel data={parsed} />
      <KpiDashboard rel={rel} falseRed={falseRed} switches={switches} standing={standing} ttcMismatch={ttcMismatch} />

      <section className="grid">
        <div className="card">
          <h2>Timeline událostí</h2>
          <LineChart values={visibleGroups.map((g) => ({ ts: g.tsStart, v: Math.max(0, ...g.riskRows.map((r) => r.risk ?? 0)) }))} />
          <div className="filterRow">
            <label>Filtr:</label>
            <select value={severityFilter} onChange={(e) => setSeverityFilter(e.target.value as 'all' | Severity)}>
              <option value="all">Vše</option>
              <option value="critical">Kritické</option>
              <option value="watch">Ke kontrole</option>
              <option value="info">Info</option>
            </select>
          </div>
          <div className="timeline">
            {visibleGroups.map((g) => (
              <button key={g.id} className={`timelineItem ${g.severity}`} onClick={() => { setSelectedId(g.id); setDetailOpen(true); }}>
                <b>{fmt(g.tsStart)}</b> - {g.summary}
                <small>{g.recommendation}</small>
              </button>
            ))}
          </div>
        </div>

        <div className="card">
          <h2>Tabulka událostí</h2>
          <div className="tableWrap">
            <table>
              <thead>
                <tr>
                  <th>čas</th><th>severity</th><th>risk max</th><th>level max</th><th>lock vs final target</th><th>doporučení</th>
                </tr>
              </thead>
              <tbody>
                {visibleGroups.map((g) => {
                  const maxRisk = Math.max(0, ...g.riskRows.map((r) => r.risk ?? 0));
                  const maxLevel = Math.max(0, ...g.riskRows.map((r) => r.level ?? 0));
                  const lockSet = new Set(g.metricsRows.map((m) => m.lockId).filter((x) => x != null));
                  const finalSet = new Set(g.riskRows.map((r) => r.finalTargetId).filter((x) => x != null));
                  return (
                    <tr key={g.id} onClick={() => { setSelectedId(g.id); setDetailOpen(true); }}>
                      <td>{fmt(g.tsStart)}</td>
                      <td>{g.severity}</td>
                      <td>{maxRisk.toFixed(3)}</td>
                      <td>{maxLevel}</td>
                      <td>L:{[...lockSet].join('|') || '-'} / F:{[...finalSet].join('|') || '-'}</td>
                      <td>{g.recommendation}</td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        </div>
      </section>

      <ObjectSegmentsPanel segments={segments} />

      <section className="card">
        <h2>Neznámé/extra struktury</h2>
        {!parsed?.unknownRows.length && <div className="empty">Žádné neznámé řádky.</div>}
        {!!parsed?.unknownRows.length && (
          <div className="tableWrap">
            <table>
              <thead><tr><th>source</th><th>ts</th><th>reason</th><th>raw</th></tr></thead>
              <tbody>
                {parsed.unknownRows.slice(0, 120).map((u, i) => (
                  <tr key={`${u.source}-${i}`}><td>{u.source}</td><td>{u.ts ?? '-'}</td><td>{u.reason}</td><td>{u.raw}</td></tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>

      {detailOpen && (
        <div className="overlay" onClick={() => setDetailOpen(false)}>
          <aside className="drawer" onClick={(e) => e.stopPropagation()}>
            <button className="close" onClick={() => setDetailOpen(false)}>Zavřít detail</button>
            {!selectedGroup && <div className="empty">Žádný detail není otevřen.</div>}
            {selectedGroup && (
              <>
                <h3>Detail události</h3>
                <p><b>Okno:</b> {fmt(selectedGroup.tsStart)} → {fmt(selectedGroup.tsEnd)}</p>
                <p><b>Soubory:</b> {selectedGroup.sources.join(', ')}</p>
                <h4>Reason bits (human)</h4>
                <ul>
                  {Array.from(new Set(selectedGroup.riskRows.flatMap((r) => decodeReasons(r.reasonBits ?? 0)))).map((d) => <li key={d}>{d}</li>)}
                </ul>
                <h4>MCAW výpočty (sample)</h4>
                <ul>
                  {selectedGroup.riskRows.slice(0, 8).map((r) => (
                    <li key={r.ts}>{fmt(r.ts)} | risk={r.risk ?? 'n/a'} level={r.level ?? 'n/a'} ttc={r.ttc ?? 'n/a'} dist={r.dist ?? 'n/a'} rel={r.relV ?? 'n/a'} ttcH={r.ttcH ?? 'n/a'} ttcD={r.ttcD ?? 'n/a'} wd={r.ttcWd ?? 'n/a'} mr={r.ttcMr ?? 'n/a'} sanity={r.ttcSanity ?? 'n/a'} lock={r.lockId ?? '-'} final={r.finalTargetId ?? '-'}</li>
                  ))}
                </ul>
                <h4>Extra fields</h4>
                <pre>{JSON.stringify(selectedGroup.riskRows.slice(0, 3).map((r) => r.extraFields), null, 2)}</pre>
                <h4>TTC mismatch windows (top 10)</h4>
                <ul>
                  {ttcMismatch.windows.map((w, i) => (
                    <li key={`${w.tsStart}-${i}`}>{fmt(w.tsStart)} → {fmt(w.tsEnd)} | frames={w.count} | min ratio={Number.isFinite(w.minRatio) ? w.minRatio.toFixed(3) : 'n/a'}</li>
                  ))}
                </ul>
              </>
            )}
          </aside>
        </div>
      )}

      {!detailOpen && <div className="detailClosed">Žádný detail není otevřen.</div>}
    </div>
  );
}

function fmt(ts: number): string {
  return new Date(ts).toLocaleString('cs-CZ', { hour12: false });
}

function decodeReasons(reasonBits: number): string[] {
  if (!reasonBits) return [];
  return REASON_BITS.filter((r) => (reasonBits & r.bit) !== 0).map((r) => `${r.key}: ${r.text}`);
}

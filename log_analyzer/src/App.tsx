import { useMemo, useState } from 'react';

type Severity = 'info' | 'watch' | 'critical';

type RiskRow = {
  ts: number;
  risk: number;
  level: number;
  state: string;
  reasonBits: number;
  ttc?: number;
  dist?: number;
  relV?: number;
  roi?: number;
  quality?: number;
  cutIn?: number;
  brake?: number;
  egoBrake?: number;
  mode?: number;
  lockedId?: string;
  label?: string;
  detScore?: number;
  reasonId?: number;
};

type TrackRow = {
  ts: number;
  id: number;
  consecutive: number;
  relDerivValid: number;
  relInvalidReasonMask: number;
  distanceInputRaw: number;
  distanceInput: number;
  distanceM: number;
  relSignedEma: number;
  approachSpeed: number;
  ttcFromDist: number;
  ttc: number;
  riskScore: number;
  level: number;
  reasonPayload: number;
  reasonId: number;
};

type EventGroup = {
  id: string;
  tsStart: number;
  tsEnd: number;
  sourceFiles: string[];
  serviceLogs: string[];
  riskRows: RiskRow[];
  trackRows: TrackRow[];
  severity: Severity;
  summary: string;
  recommendation: string;
};

type ParseResult = {
  groups: EventGroup[];
  accepted: string[];
  ignored: string[];
  errors: string[];
};

const TARGET_DATE = '2026-02-25';

const REASON_BITS: Array<{ bit: number; key: string; text: string }> = [
  { bit: 1 << 0, key: 'TTC', text: 'Kritické Time-To-Collision (TTC).' },
  { bit: 1 << 1, key: 'DIST', text: 'Nebezpečně krátká vzdálenost.' },
  { bit: 1 << 2, key: 'REL', text: 'Vysoká přibližovací rychlost.' },
  { bit: 1 << 3, key: 'ROI_LOW', text: 'Objekt je mimo optimální ROI.' },
  { bit: 1 << 4, key: 'BRAKE_CUE', text: 'Detekován brzdný podnět.' },
  { bit: 1 << 5, key: 'CUT_IN', text: 'Objekt se zařezává do trajektorie.' },
  { bit: 1 << 6, key: 'EGO_BRAKE', text: 'Jezdec/brzda už indikuje reakci.' },
  { bit: 1 << 7, key: 'QCONSERV', text: 'Konzervativní mód kvůli kvalitě obrazu.' },
  { bit: 1 << 10, key: 'RED_COMBO_OK', text: 'Podmínky pro RED kombinaci splněny.' },
  { bit: 1 << 11, key: 'RED_GUARDED', text: 'RED byl hlídán hysterézí/guard logikou.' },
  { bit: 1 << 14, key: 'DIST_DYN', text: 'Použity dynamické prahy vzdálenosti.' },
  { bit: 1 << 16, key: 'SUPPRESS_OVERTAKE', text: 'Potlačeno jako sousední předjíždění.' },
  { bit: 1 << 17, key: 'SUPPRESS_RECEDING', text: 'Potlačeno jako vzdalující se objekt.' },
  { bit: 1 << 18, key: 'SUPPRESS_STANDING', text: 'Potlačeno jako stojící objekt.' },
];

function parseCsv(line: string): string[] {
  const out: string[] = [];
  let cur = '';
  let inQ = false;
  for (let i = 0; i < line.length; i += 1) {
    const ch = line[i];
    if (ch === '"') {
      inQ = !inQ;
      continue;
    }
    if (ch === ',' && !inQ) {
      out.push(cur);
      cur = '';
      continue;
    }
    cur += ch;
  }
  out.push(cur);
  return out;
}

const toNum = (value: string): number | undefined => {
  if (!value || value === 'NaN' || value === 'Infinity' || value === '-Infinity') return undefined;
  const normalized = value.replace(',', '.');
  const n = Number(normalized);
  return Number.isFinite(n) ? n : undefined;
};

function decodeReasons(reasonBits: number): string[] {
  if (!reasonBits) return [];
  return REASON_BITS.filter((r) => (reasonBits & r.bit) !== 0).map((r) => `${r.key}: ${r.text}`);
}

function deriveSeverity(group: Omit<EventGroup, 'severity' | 'summary' | 'recommendation' | 'id'>): Pick<EventGroup, 'severity' | 'summary' | 'recommendation' | 'id'> {
  const maxLevel = Math.max(...group.riskRows.map((r) => r.level), 0);
  const maxRisk = Math.max(...group.riskRows.map((r) => r.risk), 0);
  const hasCutIn = group.riskRows.some((r) => (r.cutin ?? 0) > 0);
  const hasBrakeCue = group.riskRows.some((r) => (r.brake ?? 0) > 0 || (r.egoBrake ?? 0) > 0);

  let severity: Severity = 'info';
  if (maxLevel >= 2 || maxRisk >= 0.75) severity = 'critical';
  else if (maxLevel >= 1 || maxRisk >= 0.45 || hasCutIn || hasBrakeCue) severity = 'watch';

  const summary = maxLevel >= 2
    ? 'Riziko RED – kritická situace.'
    : maxLevel >= 1
      ? 'Riziko ORANGE – situace vyžaduje pozornost.'
      : 'SAFE/nízké riziko.';

  const recommendation = severity === 'critical'
    ? 'Prioritně analyzujte tuto skupinu: zkontrolujte TTC, distance, rel_v a důvody reason bits.'
    : severity === 'watch'
      ? 'Stojí za kontrolu: ověřte trend risk score a případné cut-in/brake signály.'
      : 'Informační skupina, vhodná pro kontext před/po incidentu.';

  return {
    id: `${group.tsStart}-${group.tsEnd}-${severity}`,
    severity,
    summary,
    recommendation,
  };
}

function parseFiles(files: File[]): Promise<ParseResult> {
  return Promise.all(files.map(async (file) => ({ file, text: await file.text() }))).then((loaded) => {
    const accepted: string[] = [];
    const ignored: string[] = [];
    const errors: string[] = [];
    const riskRows: RiskRow[] = [];
    const serviceLogs: Array<{ ts: number; message: string; file: string }> = [];
    const trackRows: TrackRow[] = [];

    for (const { file, text } of loaded) {
      if (!file.name.includes(TARGET_DATE)) {
        ignored.push(file.name);
        continue;
      }
      accepted.push(file.name);
      const lines = text.split(/\r?\n/).filter(Boolean);

      for (const line of lines) {
        const cols = parseCsv(line);
        if (!cols.length) continue;

        if (cols[0] === 'S' && cols.length >= 3) {
          const ts = Number(cols[1]);
          if (Number.isFinite(ts)) serviceLogs.push({ ts, message: cols.slice(2).join(','), file: file.name });
          continue;
        }

        if (cols[0] === 'M' && cols[2] === 'METRICS') {
          const ts = Number(cols[1]);
          if (!Number.isFinite(ts)) continue;
          trackRows.push({
            ts,
            id: Number(cols[3] ?? -1),
            consecutive: Number(cols[4] ?? 0),
            relDerivValid: Number(cols[11] ?? 0),
            relInvalidReasonMask: Number(cols[12] ?? 0),
            distanceInputRaw: Number(cols[17] ?? Number.NaN),
            distanceInput: Number(cols[18] ?? Number.NaN),
            distanceM: Number(cols[19] ?? Number.NaN),
            relSignedEma: Number(cols[20] ?? Number.NaN),
            approachSpeed: Number(cols[21] ?? Number.NaN),
            ttcFromDist: Number(cols[22] ?? Number.NaN),
            ttc: Number(cols[23] ?? Number.NaN),
            riskScore: Number(cols[24] ?? Number.NaN),
            level: Number(cols[25] ?? 0),
            reasonPayload: Number(cols[26] ?? 0),
            reasonId: Number(cols[27] ?? 0),
          });
          continue;
        }

        if (/^\d+$/.test(cols[0]) && cols.length >= 4 && cols[1] !== 'TRACK') {
          const ts = Number(cols[0]);
          const risk = toNum(cols[1]);
          if (!Number.isFinite(ts) || risk === undefined) continue;
          riskRows.push({
            ts,
            risk,
            level: Number(cols[2] ?? 0),
            state: cols[3] ?? '',
            reasonBits: Number(cols[4] ?? 0),
            ttc: toNum(cols[5]),
            dist: toNum(cols[6]),
            relV: toNum(cols[7]),
            roi: toNum(cols[8]),
            quality: toNum(cols[9]),
            cutin: toNum(cols[10]),
            brake: toNum(cols[11]),
            egoBrake: toNum(cols[12]),
            mode: toNum(cols[13]),
            lockedId: cols[14],
            label: cols[15],
            detScore: toNum(cols[16]),
            reasonId: toNum(cols[17]),
          });
          continue;
        }
      }
    }

    if (!riskRows.length && accepted.length) {
      errors.push('V souborech z 25.2 nebyly nalezeny risk řádky.');
    }

    const merged = [...riskRows.map((r) => r.ts), ...serviceLogs.map((s) => s.ts), ...trackRows.map((m) => m.ts)].sort((a, b) => a - b);
    const groups: EventGroup[] = [];
    if (merged.length) {
      let start = merged[0];
      let end = merged[0];
      for (let i = 1; i < merged.length; i += 1) {
        if (merged[i] - end <= 350) {
          end = merged[i];
        } else {
          groups.push(buildGroup(start, end));
          start = merged[i];
          end = merged[i];
        }
      }
      groups.push(buildGroup(start, end));
    }

    function buildGroup(tsStart: number, tsEnd: number): EventGroup {
      const rg = riskRows.filter((r) => r.ts >= tsStart && r.ts <= tsEnd);
      const sg = serviceLogs.filter((s) => s.ts >= tsStart && s.ts <= tsEnd);
      const mg = trackRows.filter((m) => m.ts >= tsStart && m.ts <= tsEnd);
      const meta = deriveSeverity({
        tsStart,
        tsEnd,
        sourceFiles: [...new Set(sg.map((s) => s.file))],
        serviceLogs: sg.map((s) => s.message),
        riskRows: rg,
        trackRows: mg,
      });
      return {
        ...meta,
        tsStart,
        tsEnd,
        sourceFiles: [...new Set([...sg.map((s) => s.file)])],
        serviceLogs: sg.map((s) => s.message),
        riskRows: rg,
        trackRows: mg,
      };
    }

    return {
      groups: groups.sort((a, b) => a.tsStart - b.tsStart),
      accepted,
      ignored,
      errors,
    };
  });
}

function formatTime(ts: number): string {
  return new Date(ts).toLocaleTimeString('cs-CZ', { hour12: false }) + `.${String(ts % 1000).padStart(3, '0')}`;
}

function MiniChart({ groups }: { groups: EventGroup[] }) {
  const points = groups
    .map((g) => {
      const top = g.riskRows.reduce((acc, r) => Math.max(acc, r.risk), 0);
      return { x: g.tsStart, y: top };
    })
    .filter((p) => Number.isFinite(p.y));

  if (!points.length) return <div className="empty">Po načtení logu zde uvidíte trend risk score.</div>;

  const minX = points[0].x;
  const maxX = points[points.length - 1].x;
  const path = points
    .map((p, i) => {
      const x = ((p.x - minX) / Math.max(1, maxX - minX)) * 100;
      const y = (1 - Math.min(1, Math.max(0, p.y))) * 100;
      return `${i === 0 ? 'M' : 'L'} ${x} ${y}`;
    })
    .join(' ');

  return (
    <svg viewBox="0 0 100 100" className="chart" preserveAspectRatio="none">
      <line x1="0" x2="100" y1="75" y2="75" className="line warn" />
      <line x1="0" x2="100" y1="45" y2="45" className="line red" />
      <path d={path} className="riskPath" />
    </svg>
  );
}

export function App() {
  const [groups, setGroups] = useState<EventGroup[]>([]);
  const [selected, setSelected] = useState<string>('');
  const [accepted, setAccepted] = useState<string[]>([]);
  const [ignored, setIgnored] = useState<string[]>([]);
  const [errors, setErrors] = useState<string[]>([]);
  const [severityFilter, setSeverityFilter] = useState<'all' | Severity>('all');

  const selectedGroup = useMemo(() => groups.find((g) => g.id === selected) ?? groups[0], [groups, selected]);
  const visibleGroups = useMemo(
    () => groups.filter((g) => (severityFilter === 'all' ? true : g.severity === severityFilter)),
    [groups, severityFilter]
  );

  return (
    <div className="page">
      <header>
        <h1>MCAW log_analyzer (první verze)</h1>
        <p>Nástroj načte jen logy s datem <b>25.2.2026</b>, seskupí související události a přeloží je do lidského jazyka.</p>
      </header>

      <section className="card controls">
        <input
          type="file"
          multiple
          accept=".txt,.csv"
          onChange={async (ev) => {
            const list = Array.from(ev.target.files ?? []);
            const result = await parseFiles(list);
            setGroups(result.groups);
            setSelected(result.groups[0]?.id ?? '');
            setAccepted(result.accepted);
            setIgnored(result.ignored);
            setErrors(result.errors);
          }}
        />
        <div className="chips">
          {accepted.map((n) => <span key={n} className="chip ok">✓ {n}</span>)}
          {ignored.map((n) => <span key={n} className="chip mute">Ignorováno (jiné datum): {n}</span>)}
          {errors.map((e) => <span key={e} className="chip err">{e}</span>)}
        </div>
      </section>

      <section className="grid">
        <div className="card">
          <h2>Timeline doporučení</h2>
          <MiniChart groups={visibleGroups} />
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
              <button key={g.id} className={`timelineItem ${g.severity} ${selectedGroup?.id === g.id ? 'active' : ''}`} onClick={() => setSelected(g.id)}>
                <b>{formatTime(g.tsStart)}</b> — {g.summary}
                <small>{g.recommendation}</small>
              </button>
            ))}
          </div>
        </div>

        <div className="card">
          <h2>Detail události</h2>
          {!selectedGroup && <div className="empty">Vyberte událost z timeline.</div>}
          {selectedGroup && (
            <div className="details">
              <p><b>Okno:</b> {formatTime(selectedGroup.tsStart)} → {formatTime(selectedGroup.tsEnd)}</p>
              <p><b>Soubory:</b> {selectedGroup.sourceFiles.join(', ') || '-'}</p>
              <p><b>Shrnutí:</b> {selectedGroup.summary}</p>
              <p><b>Doporučení:</b> {selectedGroup.recommendation}</p>

              <h3>Lidský překlad reason bits</h3>
              <ul>
                {Array.from(new Set(selectedGroup.riskRows.flatMap((r) => decodeReasons(r.reasonBits)))).map((d) => (
                  <li key={d}>{d}</li>
                ))}
                {selectedGroup.riskRows.length === 0 && <li>V tomto okně nejsou risk řádky.</li>}
              </ul>

              <h3>Použité výpočty MCAW (z logu)</h3>
              <ul>
                {selectedGroup.riskRows.slice(0, 4).map((r) => (
                  <li key={`${r.ts}-${r.reasonBits}`}>
                    {formatTime(r.ts)} → risk={r.risk.toFixed(3)}, level={r.level}, ttc={r.ttc ?? 'n/a'}, dist={r.dist ?? 'n/a'}m,
                    rel_v={r.relV ?? 'n/a'}m/s, roi={r.roi ?? 'n/a'}, quality={r.quality ?? 'n/a'}, mode={r.mode ?? 'n/a'}.
                  </li>
                ))}
              </ul>

              <h3>Service log texty</h3>
              <pre>{selectedGroup.serviceLogs.slice(0, 14).join('\n') || '-'}</pre>
            </div>
          )}
        </div>
      </section>

      <section className="card">
        <h2>Tabulka událostí (sticky hlavička + scroll uvnitř)</h2>
        <div className="tableWrap">
          <table>
            <thead>
              <tr>
                <th>Čas</th>
                <th>Severity</th>
                <th>Risk max</th>
                <th>Level max</th>
                <th>Reason bits</th>
                <th>Doporučení</th>
              </tr>
            </thead>
            <tbody>
              {visibleGroups.map((g) => {
                const maxRisk = g.riskRows.reduce((acc, r) => Math.max(acc, r.risk), 0);
                const maxLevel = g.riskRows.reduce((acc, r) => Math.max(acc, r.level), 0);
                const bits = Array.from(new Set(g.riskRows.map((r) => r.reasonBits).filter((x) => x > 0))).slice(0, 3);
                return (
                  <tr key={`row-${g.id}`} onClick={() => setSelected(g.id)} className={selectedGroup?.id === g.id ? 'selected' : ''}>
                    <td>{formatTime(g.tsStart)}</td>
                    <td>{g.severity}</td>
                    <td>{maxRisk.toFixed(3)}</td>
                    <td>{maxLevel}</td>
                    <td>{bits.join(', ') || '-'}</td>
                    <td>{g.recommendation}</td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      </section>
    </div>
  );
}

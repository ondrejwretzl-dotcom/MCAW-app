import React, { useMemo, useState } from 'react';
import { parseJsonl } from '../io/jsonl';
import { RiskEngineRef } from '../engine/RiskEngine';
import { fuseTtc } from '../engine/ttcFusion';

import { UploadPanel } from './components/UploadPanel';
import { BuilderPanel } from './components/BuilderPanel';
import { SummaryCards } from './components/SummaryCards';
import { PlotsPanel } from './components/PlotsPanel';
import { ResultsTable, TableRow } from './components/ResultsTable';
import { MdPanel } from './components/MdPanel';
import { WarningsPanel } from './components/WarnPanel';

import { ScenarioDoc, FrameRow, FrameIn, FrameOut, WhatIfConfig, Thresholds } from './lib/types';
import { safeBaseName, mpsToKmh, clamp, downloadText, toCsv, round3 } from './lib/utils';
import { ScenarioDraft, generateFrames } from './lib/scenario';
import { validateScenario } from './lib/validate';
import { buildMcawKotlinSnippet, buildMcawMarkdown, buildMcawSpec, defaultDraftMeta, DraftMeta } from './lib/mcaw';
import { RelStabilityState } from './lib/relStability';
import { templateC2 } from './lib/templates';

type DataSource = 'upload' | 'builder';

function defaultWhatIf(): WhatIfConfig {
  return {
    enabled: false,
    dynamicDistanceEnabled: true,
    orangeGapSec: 2.0,
    redGapSec: 1.2,
    distOrangeClampMinM: 6,
    distOrangeClampMaxM: 80,
    distRedClampMinM: 4,
    distRedClampMaxM: 60,
  };
}

function defaultDraft(): ScenarioDraft {
  return templateC2();
}

function isFrameEvent(x: any): boolean {
  return x && (x.type === 'FRAME' || (x.in && x.out) || (x.in && x.tSec !== undefined));
}

async function readFiles(files: FileList): Promise<ScenarioDoc[]> {
  const byId: Record<string, ScenarioDoc> = {};
  for (const f of Array.from(files)) {
    const base = safeBaseName(f.name);
    if (!byId[base]) byId[base] = { scenarioId: base, frames: [], sourceFileBase: base };

    if (f.name.toLowerCase().endsWith('.md')) {
      byId[base].notesMd = await f.text();
      continue;
    }

    if (f.name.toLowerCase().endsWith('.jsonl')) {
      const text = await f.text();
      const items = parseJsonl(text).filter(isFrameEvent);
      const framesByScenario: Record<string, FrameRow[]> = {};
      for (const it of items) {
        const tSec = Number(it.tSec ?? it.t ?? 0);
        const input: FrameIn = it.in ?? it.input ?? {};
        const out: FrameOut | undefined = it.out ?? it.output ?? it.outKotlin;
        const scenarioFromLine = String(it.scenario ?? it.scenarioId ?? '').trim();
        const id = scenarioFromLine.length > 0 ? scenarioFromLine : base;
        // Guard required fields
        if (input.distanceM === undefined || input.approachSpeedMps === undefined || input.ttcSec === undefined) continue;
        if (!framesByScenario[id]) framesByScenario[id] = [];
        framesByScenario[id].push({ scenarioId: id, tSec, in: input, outKotlin: out });
      }
      for (const [id, frames] of Object.entries(framesByScenario)) {
        if (!byId[id]) byId[id] = { scenarioId: id, frames: [], sourceFileBase: base };
        byId[id].frames = byId[id].frames.concat(frames);
      }
    }
  }
  return Object.values(byId)
    .map((s) => ({ ...s, frames: [...s.frames].sort((a, b) => a.tSec - b.tSec) }))
    .filter((s) => s.frames.length > 0);
}

function computeFirstTimes(levels: number[], t: number[]): { firstOrange: number | null; firstRed: number | null } {
  let firstOrange: number | null = null;
  let firstRed: number | null = null;
  for (let i = 0; i < levels.length; i++) {
    if (firstOrange == null && levels[i] >= 1) firstOrange = t[i];
    if (firstRed == null && levels[i] >= 2) firstRed = t[i];
  }
  return { firstOrange, firstRed };
}

export function App() {
  const [dataSource, setDataSource] = useState<DataSource>('upload');

  // Upload state
  const [uploaded, setUploaded] = useState<ScenarioDoc[]>([]);
  const [selectedUploadId, setSelectedUploadId] = useState<string | null>(null);

  // Builder state
  const [draft, setDraft] = useState<ScenarioDraft>(defaultDraft());
  const [mcawMeta, setMcawMeta] = useState<DraftMeta>(defaultDraftMeta(defaultDraft().scenarioId));
  const [autoRecompute, setAutoRecompute] = useState<boolean>(false);

  // Controls
  const [whatIf, setWhatIf] = useState<WhatIfConfig>(defaultWhatIf());
  const [showOnlyDiffs, setShowOnlyDiffs] = useState<boolean>(false);

  const [showInputPlot, setShowInputPlot] = useState<boolean>(false);
  const [inputEnabled, setInputEnabled] = useState<Record<string, boolean>>({
    'speed (km/h)': true,
    'distance (m)': true,
    'rel (m/s)': true,
    'TTC (s)': false,
  });

  const activeScenario: ScenarioDoc | null = useMemo(() => {
    if (dataSource !== 'upload') return null;
    if (uploaded.length === 0) return null;
    const id = selectedUploadId ?? uploaded[0].scenarioId;
    return uploaded.find((s) => s.scenarioId === id) ?? uploaded[0];
  }, [dataSource, uploaded, selectedUploadId]);

  const activeFrames: FrameRow[] = useMemo(() => {
    if (dataSource === 'upload') return activeScenario?.frames ?? [];
    return generateFrames(draft);
  }, [dataSource, activeScenario, draft]);

  const warnings = useMemo(() => {
    if (dataSource !== 'builder') return [];
    return validateScenario(draft, whatIf);
  }, [dataSource, draft, whatIf]);

  const simulation = useMemo(() => {
    const frames = activeFrames;
    if (frames.length === 0) return null;

    const engBase = new RiskEngineRef();
    const engTuned = new RiskEngineRef();

    const t: number[] = [];
    const baseRisk: number[] = [];
    const baseLevel: number[] = [];
    const baseReason: number[] = [];
    const baseRaw: number[] = [];
    const baseEma: number[] = [];

    const tunedRisk: number[] = [];
    const tunedLevel: number[] = [];
    const tunedReason: number[] = [];
    const tunedRaw: number[] = [];
    const tunedEma: number[] = [];
    const tunedDistOrange: number[] = [];
    const tunedDistRed: number[] = [];
    const relDerivedMps: number[] = [];
    const relDerivedValid: boolean[] = [];

    let baseThrFull: any = null;

    let mismatch = 0;
    const relState = new RelStabilityState();

    for (const fr of frames) {
      const input = fr.in;
      const relFrame = relState.step({
        tsMs: Math.round(fr.tSec * 1000),
        distanceM: Number(input.distanceM),
        hasBest: Boolean(input.hasBest ?? true),
        bestId: input.bestId === undefined ? undefined : Number(input.bestId),
        bottomOccluded: input.bottomOccluded,
        riderSpeedKnown: Number.isFinite(Number(input.riderSpeedMps)),
      });

      relDerivedMps.push(Number(relFrame.relSignedEmaMps));
      relDerivedValid.push(Boolean(relFrame.relDerivValid));

      const approachForRisk = Number(relFrame.relDerivValid ? Math.abs(relFrame.relSignedEmaMps) : Number(input.approachSpeedMps ?? 0));
      const ttcHeightSec = Number(input.ttcHeightSec ?? input.ttcSec);
      const ttcDistSec = Number(input.ttcDistSec ?? input.ttcSec);
      const ttcFusion = fuseTtc(
        ttcHeightSec,
        ttcDistSec,
        Number(relFrame.distanceStableM),
        approachForRisk,
        Boolean(input.bottomOccluded),
        false,
        Number(input.qualityWeight ?? 1),
      );

      const evalInput: FrameIn = {
        ...input,
        distanceM: Number(relFrame.distanceStableM),
        approachSpeedMps: approachForRisk,
        ttcSec: ttcFusion.ttcFused,
        ttcHeightSec,
        ttcDistSec,
        suppressRecedingHard: relFrame.trendState === 2,
        suppressSteadyGapHard: relFrame.steadySuppressActive,
        effectiveMode: Number(input.effectiveMode ?? 1),
        roiContainment: Number(input.roiContainment ?? 1),
        egoOffsetN: Number(input.egoOffsetN ?? 0),
        brakeCueStrength: Number(input.brakeCueStrength ?? 0),
        riderSpeedMps: Number(input.riderSpeedMps ?? 0),
        riderSpeedConfidence: Number(input.riderSpeedConfidence ?? 1),
        egoBrakingConfidence: Number(input.egoBrakingConfidence ?? 0),
        qualityWeight: Number(input.qualityWeight ?? 1),
        cutInActive: !!input.cutInActive,
        brakeCueActive: !!input.brakeCueActive,
      };

      const base = (engBase as any).evaluate(evalInput);
      const effectiveMode = Number(evalInput.effectiveMode ?? 1);
      const qualityWeight = Number(evalInput.qualityWeight ?? 1);
      baseThrFull = baseThrFull ?? (engBase as any).debugDerivedThresholds(effectiveMode, qualityWeight);

      t.push(fr.tSec);
      baseRisk.push(Number(base.riskScore ?? 0));
      baseLevel.push(Number(base.level ?? 0));
      baseReason.push(Number(base.reasonBits ?? 0));
      baseRaw.push(Number(base.rawRisk ?? base.riskScore ?? 0));
      baseEma.push(Number(base.emaRisk ?? base.riskScore ?? 0));

      if (fr.outKotlin) {
        if (Number(fr.outKotlin.level) !== Number(base.level) || Number(fr.outKotlin.reasonBits) !== Number(base.reasonBits)) {
          mismatch++;
        }
      }

      if (whatIf.enabled) {
        let tuned = null as any;
        let distO = NaN;
        let distR = NaN;

        if (whatIf.dynamicDistanceEnabled) {
          const v = Number(evalInput.riderSpeedMps ?? 0);
          if (Number.isFinite(v) && v > 0.1) {
            const thr = (engTuned as any).debugDerivedThresholds(effectiveMode, qualityWeight, undefined, {
              dynamicDistanceEnabled: true,
              dynamicDistanceOrangeSec: whatIf.orangeGapSec,
              dynamicDistanceRedSec: whatIf.redGapSec,
            });
            distO = clamp(v * whatIf.orangeGapSec, whatIf.distOrangeClampMinM, whatIf.distOrangeClampMaxM);
            distR = clamp(v * whatIf.redGapSec, whatIf.distRedClampMinM, whatIf.distRedClampMaxM);
            const override = {
              ttcOrange: thr.ttcOrange,
              ttcRed: thr.ttcRed,
              distOrange: distO,
              distRed: distR,
              relOrange: thr.relOrange,
              relRed: thr.relRed,
            };
            tuned = (engTuned as any).evaluate(evalInput, override, {
              dynamicDistanceEnabled: true,
              dynamicDistanceOrangeSec: whatIf.orangeGapSec,
              dynamicDistanceRedSec: whatIf.redGapSec,
            });
          } else {
            tuned = (engTuned as any).evaluate(evalInput, undefined, {
              dynamicDistanceEnabled: true,
              dynamicDistanceOrangeSec: whatIf.orangeGapSec,
              dynamicDistanceRedSec: whatIf.redGapSec,
            });
          }
        } else {
          tuned = (engTuned as any).evaluate(evalInput, undefined, {
            dynamicDistanceEnabled: false,
            dynamicDistanceOrangeSec: whatIf.orangeGapSec,
            dynamicDistanceRedSec: whatIf.redGapSec,
          });
        }

        tunedRisk.push(Number(tuned.riskScore ?? 0));
        tunedLevel.push(Number(tuned.level ?? 0));
        tunedReason.push(Number(tuned.reasonBits ?? 0));
        tunedRaw.push(Number(tuned.rawRisk ?? tuned.riskScore ?? 0));
        tunedEma.push(Number(tuned.emaRisk ?? tuned.riskScore ?? 0));
        tunedDistOrange.push(distO);
        tunedDistRed.push(distR);
      }
    }

    // thresholds
    const thr: Thresholds = {
      ttcOrange: Number(baseThrFull?.ttcOrange ?? 3),
      ttcRed: Number(baseThrFull?.ttcRed ?? 2),
      distOrangeM: Number(baseThrFull?.distOrange ?? 15),
      distRedM: Number(baseThrFull?.distRed ?? 8),
      relOrange: Number(baseThrFull?.relOrange ?? 3),
      relRed: Number(baseThrFull?.relRed ?? 5),
      orangeOn: Number(baseThrFull?.orangeOn ?? 0.45),
      orangeOff: Number(baseThrFull?.orangeOff ?? 0.39),
      redOn: Number(baseThrFull?.redOn ?? 0.75),
      redOff: Number(baseThrFull?.redOff ?? 0.7),
    };

    const baseTimes = computeFirstTimes(baseLevel, t);
    const tunedTimes = whatIf.enabled ? computeFirstTimes(tunedLevel, t) : { firstOrange: null, firstRed: null };

    // Input series
    const speedKmh = frames.map((f) => mpsToKmh(Number(f.in.riderSpeedMps ?? 0)));
    const distM = frames.map((f) => Number(f.in.distanceM));
    const relMps = relDerivedMps.map((v, i) => (relDerivedValid[i] ? v : Number.NaN));
    const ttcSec = frames.map((f) => Number(f.in.ttcSec));

    // Table rows
    const rows: TableRow[] = frames.map((f, i) => ({
      tSec: f.tSec,
      distanceM: Number(f.in.distanceM),
      relMps: relDerivedValid[i] ? Number(relDerivedMps[i]) : Number.NaN,
      ttcSec: Number(f.in.ttcSec),
      speedKmh: mpsToKmh(Number(f.in.riderSpeedMps ?? 0)),
      baseRisk: baseRisk[i],
      baseLevel: baseLevel[i],
      baseReasonBits: baseReason[i],
      tunedRisk: whatIf.enabled ? tunedRisk[i] : undefined,
      tunedLevel: whatIf.enabled ? tunedLevel[i] : undefined,
      tunedReasonBits: whatIf.enabled ? tunedReason[i] : undefined,
      distOrangeM: whatIf.enabled ? tunedDistOrange[i] : undefined,
      distRedM: whatIf.enabled ? tunedDistRed[i] : undefined,
    }));

    // CSV rows
    const csvRows = rows.map((r) => ({
      tSec: round3(r.tSec),
      distanceM: round3(r.distanceM),
      relMps: round3(r.relMps),
      ttcSec: round3(r.ttcSec),
      speedKmh: round3(r.speedKmh),
      baseRisk: round3(r.baseRisk),
      baseLevel: r.baseLevel,
      baseReasonBits: r.baseReasonBits,
      tunedRisk: r.tunedRisk == null ? '' : round3(r.tunedRisk),
      tunedLevel: r.tunedLevel ?? '',
      tunedReasonBits: r.tunedReasonBits ?? '',
      distOrangeM: r.distOrangeM == null ? '' : round3(r.distOrangeM),
      distRedM: r.distRedM == null ? '' : round3(r.distRedM),
    }));

    return {
      t,
      base: { risk: baseRisk, level: baseLevel, reason: baseReason, raw: baseRaw, ema: baseEma, first: baseTimes },
      tuned: whatIf.enabled ? { risk: tunedRisk, level: tunedLevel, reason: tunedReason, raw: tunedRaw, ema: tunedEma, first: tunedTimes, distOrange: tunedDistOrange, distRed: tunedDistRed } : null,
      thresholds: thr,
      mismatchCount: frames.some((f) => !!f.outKotlin) ? mismatch : null,
      hasKotlinOut: frames.some((f) => !!f.outKotlin),
      input: { speedKmh, distM, relMps, ttcSec },
      rows,
      csvRows,
    };
  }, [activeFrames, whatIf]);

  const inputSeries = useMemo(() => {
    if (!simulation) return [];
    const t = simulation.t;
    const items = [
      { name: 'speed (km/h)', x: t, y: simulation.input.speedKmh },
      { name: 'distance (m)', x: t, y: simulation.input.distM },
      { name: 'rel (m/s)', x: t, y: simulation.input.relMps },
      { name: 'TTC (s)', x: t, y: simulation.input.ttcSec },
    ];
    return items.map((it) => ({ ...it, enabled: !!inputEnabled[it.name] }));
  }, [simulation, inputEnabled]);

  const downloadCsv = () => {
    if (!simulation) return;
    const id = dataSource === 'upload' ? (activeScenario?.scenarioId ?? 'scenario') : (draft.scenarioId || 'scenario');
    downloadText(`${id}.csv`, toCsv(simulation.csvRows), 'text/csv');
  };


  const buildMcawArtifacts = () => {
    return buildMcawSpec(draft, mcawMeta);
  };

  const downloadScenarioSpecJson = () => {
    const { spec } = buildMcawArtifacts();
    downloadText(`${draft.scenarioId || 'scenario'}_spec.json`, JSON.stringify(spec, null, 2), 'application/json');
  };

  const downloadScenarioMdMcaw = () => {
    const { spec, issues } = buildMcawArtifacts();
    downloadText(`${draft.scenarioId || 'scenario'}.md`, buildMcawMarkdown(spec, issues), 'text/markdown');
  };

  const downloadScenarioKotlinSnippet = () => {
    const { spec } = buildMcawArtifacts();
    downloadText(`${draft.scenarioId || 'scenario'}_kotlin.snippet.kt`, buildMcawKotlinSnippet(spec), 'text/plain');
  };

  return (
    <div style={{ padding: 18, fontFamily: 'system-ui, -apple-system, Segoe UI, Roboto, sans-serif', maxWidth: 1400, margin: '0 auto' }}>
      <h2 style={{ marginTop: 0 }}>MCAW Risk Simulator</h2>
      <div style={{ marginTop: -8, marginBottom: 10 }}>
        <span style={{ display: 'inline-block', background: '#111827', color: '#fff', padding: '4px 8px', borderRadius: 8, fontSize: 12, fontWeight: 700 }}>
          UI MARKER 2026-02-24
        </span>
      </div>

      <div style={{ display: 'flex', gap: 12, alignItems: 'center', flexWrap: 'wrap', marginBottom: 12 }}>
        <label style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
          Data source
          <select value={dataSource} onChange={(e) => setDataSource(e.target.value as DataSource)}>
            <option value="upload">Upload frames.jsonl</option>
            <option value="builder">Scenario builder</option>
          </select>
        </label>

        <label style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
          What-if
          <input type="checkbox" checked={whatIf.enabled} onChange={(e) => setWhatIf({ ...whatIf, enabled: e.target.checked })} />
        </label>

        <label style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
          Dynamic distance (time-gap)
          <input
            type="checkbox"
            checked={whatIf.dynamicDistanceEnabled}
            onChange={(e) => setWhatIf({ ...whatIf, dynamicDistanceEnabled: e.target.checked })}
            disabled={!whatIf.enabled}
          />
        </label>

        <label style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
          orangeGap(s)
          <input type="number" step={0.1} value={whatIf.orangeGapSec} onChange={(e) => setWhatIf({ ...whatIf, orangeGapSec: Number(e.target.value) })} style={{ width: 70 }} disabled={!whatIf.enabled} />
        </label>
        <label style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
          redGap(s)
          <input type="number" step={0.1} value={whatIf.redGapSec} onChange={(e) => setWhatIf({ ...whatIf, redGapSec: Number(e.target.value) })} style={{ width: 70 }} disabled={!whatIf.enabled} />
        </label>

        <label style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
          orange clamp m
          <input type="number" value={whatIf.distOrangeClampMinM} onChange={(e) => setWhatIf({ ...whatIf, distOrangeClampMinM: Number(e.target.value) })} style={{ width: 70 }} disabled={!whatIf.enabled} />
          <span>..</span>
          <input type="number" value={whatIf.distOrangeClampMaxM} onChange={(e) => setWhatIf({ ...whatIf, distOrangeClampMaxM: Number(e.target.value) })} style={{ width: 70 }} disabled={!whatIf.enabled} />
        </label>

        <label style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
          red clamp m
          <input type="number" value={whatIf.distRedClampMinM} onChange={(e) => setWhatIf({ ...whatIf, distRedClampMinM: Number(e.target.value) })} style={{ width: 70 }} disabled={!whatIf.enabled} />
          <span>..</span>
          <input type="number" value={whatIf.distRedClampMaxM} onChange={(e) => setWhatIf({ ...whatIf, distRedClampMaxM: Number(e.target.value) })} style={{ width: 70 }} disabled={!whatIf.enabled} />
        </label>

        <button onClick={downloadCsv} disabled={!simulation}>Download CSV</button>
      </div>

      {dataSource === 'upload' ? (
        <UploadPanel
          scenarios={uploaded}
          selectedScenarioId={selectedUploadId}
          onSelectScenario={(id) => setSelectedUploadId(id)}
          onFilesChosen={(files) => {
            void (async () => {
              const sc = await readFiles(files);
              setUploaded(sc);
              setSelectedUploadId(sc[0]?.scenarioId ?? null);
            })();
          }}
        />
      ) : (
        <>
          <BuilderPanel
            draft={draft}
            mcawMeta={mcawMeta}
            onChangeMeta={setMcawMeta}
            onChange={(next) => {
              setDraft(next);
              setMcawMeta((prev) => ({ ...defaultDraftMeta(next.scenarioId), ...prev, title: prev.title || next.scenarioId }));
            }}
            onRecompute={() => { /* recompute is memo-driven; trigger by state update */ setDraft({ ...draft }); }}
            autoRecompute={autoRecompute}
            onToggleAuto={setAutoRecompute}
            onDownloadScenarioMd={downloadScenarioMdMcaw}
            onDownloadScenarioSpecJson={downloadScenarioSpecJson}
            onDownloadScenarioKotlinSnippet={downloadScenarioKotlinSnippet}
          />
          <WarningsPanel warnings={warnings} />
        </>
      )}

      {dataSource === 'upload' && <MdPanel title="Show test notes (MD)" md={activeScenario?.notesMd} />}

      {simulation && (
        <>
          <SummaryCards
            baseFirstOrange={simulation.base.first.firstOrange}
            baseFirstRed={simulation.base.first.firstRed}
            tunedFirstOrange={simulation.tuned?.first.firstOrange ?? null}
            tunedFirstRed={simulation.tuned?.first.firstRed ?? null}
            mismatchCount={simulation.mismatchCount}
            hasKotlinOut={simulation.hasKotlinOut}
            hasTuned={!!simulation.tuned}
          />

          <PlotsPanel
            t={simulation.t}
            baseEma={simulation.base.ema}
            tunedEma={simulation.tuned?.ema}
            baseRaw={simulation.base.raw}
            tunedRaw={simulation.tuned?.raw}
            baseLevel={simulation.base.level}
            tunedLevel={simulation.tuned?.level}
            thresholds={simulation.thresholds}
            baseFirstOrange={simulation.base.first.firstOrange}
            baseFirstRed={simulation.base.first.firstRed}
            tunedFirstOrange={simulation.tuned?.first.firstOrange ?? null}
            tunedFirstRed={simulation.tuned?.first.firstRed ?? null}
            showInputPlot={showInputPlot}
            inputSeries={inputSeries}
            onToggleInputPlot={setShowInputPlot}
            onToggleInputSeries={(name, enabled) => setInputEnabled({ ...inputEnabled, [name]: enabled })}
          />

          <ResultsTable
            rows={simulation.rows}
            showOnlyDiffs={showOnlyDiffs}
            onToggleOnlyDiffs={setShowOnlyDiffs}
            hasTuned={!!simulation.tuned}
          />
        </>
      )}

      {!simulation && (
        <div style={{ marginTop: 18, color: '#666' }}>
          Upload a <code>*.frames.jsonl</code> file or create a scenario in builder and click Recompute.
        </div>
      )}
    </div>
  );
}


export default App;

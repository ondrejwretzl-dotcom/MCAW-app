import React, { useEffect, useMemo, useState } from 'react';
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
import { InfoTip, LabelWithInfo } from './components/InfoTip';

import { ScenarioDoc, FrameRow, FrameIn, FrameOut, WhatIfConfig, Thresholds } from './lib/types';
import { safeBaseName, mpsToKmh, clamp, downloadText, toCsv, round3 } from './lib/utils';
import { ScenarioDraft, generateFrames } from './lib/scenario';
import { validateScenario } from './lib/validate';
import { buildMcawKotlinSnippet, buildMcawMarkdown, buildMcawSpec, defaultDraftMeta, DraftMeta } from './lib/mcaw';
import { RelStabilityState } from './lib/relStability';
import { templateC2 } from './lib/templates';

type DataSource = 'upload' | 'builder';


type CustomEngineParams = {
  ttcInvalidHoldMs: number;
  ttcHeightHoldMs: number;
  smoothTtcMaxDropRate: number;
  smoothTtcAlphaDrop: number;
  minGrowthRatio: number;
  minDeltaHPx: number;
  ttcFromDistApproachGate: number;
  plateauBase: number;
  plateauMax: number;
  approachGateMin: number;
  approachLow: number;
  approachHigh: number;
};

type FieldErrors = Partial<Record<keyof CustomEngineParams, string>>;

type TtcTuneState = {
  lastTsMs: number;
  ttcHeightHeld: number;
  ttcHeightHeldTsMs: number;
  ttcEma: number;
  ttcEmaValid: boolean;
  lastTtcFiniteTsMs: number;
  prevHeightTtc: number;
};

const CUSTOM_DEFAULTS: CustomEngineParams = {
  ttcInvalidHoldMs: 400,
  ttcHeightHoldMs: 500,
  smoothTtcMaxDropRate: 12.0,
  smoothTtcAlphaDrop: 0.65,
  minGrowthRatio: 1.01,
  minDeltaHPx: 0.7,
  ttcFromDistApproachGate: 0.2,
  plateauBase: 0.45,
  plateauMax: 0.65,
  approachGateMin: 0.8,
  approachLow: 1.5,
  approachHigh: 4.5,
};

const PARAM_META: Record<keyof CustomEngineParams, { title: string; unit?: string; step: number; info: string; group: 'TTC' | 'Approach' }> = {
  ttcInvalidHoldMs: {
    title: 'Držení TTC při neplatné hodnotě',
    unit: 'ms',
    step: 10,
    group: 'TTC',
    info: 'Když TTC na chvíli „spadne“ (NaN/∞), engine může ještě krátce držet poslední platnou hodnotu.\n\n↑ Zvýšení: méně výpadků TTC → alerty mohou být stabilnější, ale mohou i déle „viset“, když TTC už není validní.\n↓ Snížení: rychlejší nulování TTC → méně falešných pokračujících alertů, ale více „dírek“ v TTC.',
  },
  ttcHeightHoldMs: {
    title: 'Držení TTC z výšky (bbox) při šumu',
    unit: 'ms',
    step: 10,
    group: 'TTC',
    info: 'Krátké podržení TTC odvozeného z trendu velikosti objektu (výška v pixelech), aby se potlačil šum.\n\n↑ Zvýšení: hladší TTC (méně jitteru) → stabilnější alerty, ale větší zpoždění na náhlé zhoršení.\n↓ Snížení: rychlejší reakce → citlivější, ale může víc „cukát“ a dělat falešné špičky.',
  },
  smoothTtcMaxDropRate: {
    title: 'Max rychlost poklesu TTC',
    unit: 's/s',
    step: 0.5,
    group: 'TTC',
    info: 'Limiter na to, jak rychle se smí TTC zhoršovat (klesat) mezi snímky.\n\n↑ Zvýšení: povolí rychlejší pokles TTC → alerty mohou přijít dřív při náhlém přiblížení.\n↓ Snížení: „brzdí“ pokles TTC → pozdější/klidnější alerty, ale riziko, že RED přijde pozdě.',
  },
  smoothTtcAlphaDrop: {
    title: 'EMA váha při poklesu TTC',
    unit: '',
    step: 0.01,
    group: 'TTC',
    info: 'Jak agresivně EMA sleduje zhoršení TTC (pokles). 0 = hodně hladké, 1 = skoro bez filtru.\n\n↑ Zvýšení: rychlejší reakce na zhoršení → dřívější alerty.\n↓ Snížení: více filtrování → stabilnější, ale pozdější alerty.',
  },
  minGrowthRatio: {
    title: 'Min růst objektu (poměr)',
    unit: '',
    step: 0.001,
    group: 'TTC',
    info: 'Minimální požadovaný růst velikosti objektu (poměr) pro „approach“ (přibližování) v TTC z obrazu.\n\n↑ Zvýšení: přísnější detekce přibližování → méně alertů / méně falešňáků, ale může přehlédnout pomalé přibližování.\n↓ Snížení: citlivější approach → více a dřívější alerty, ale vyšší riziko falešných.',
  },
  minDeltaHPx: {
    title: 'Min změna výšky objektu',
    unit: 'px',
    step: 0.1,
    group: 'TTC',
    info: 'Minimální absolutní změna výšky bounding boxu (v pixelech), aby se bral růst jako „reálný“ a ne šum.\n\n↑ Zvýšení: ignoruje malé změny → stabilnější, ale méně citlivé (pozdější alerty).\n↓ Snížení: bere i malé změny → dřívější reakce, ale víc jitteru.',
  },
  ttcFromDistApproachGate: {
    title: 'Přepínání TTC: distance vs. image',
    unit: '',
    step: 0.01,
    group: 'TTC',
    info: 'Jak moc se při nejistém přibližování opírat o TTC z distance/rel speed (když image TTC není jisté).\n\n↑ Zvýšení: více spoléhá na distance TTC → může zklidnit šum z obrazu, ale může být méně „prediktivní“ v některých scénách.\n↓ Snížení: více věří image TTC → dřívější reakce na cut-in/rychlé změny, ale větší citlivost na šum detekce.',
  },

  plateauBase: {
    title: 'Distance plateau (základ)',
    unit: '',
    step: 0.01,
    group: 'Approach',
    info: 'Základní „plateau“ pro distance score: i při vzdálenosti > ORANGE threshold může distance ještě přispívat do rizika až do této hodnoty.\n\n↑ Zvýšení: distance přispívá víc i při větších vzdálenostech → dřívější ORANGE/RED.\n↓ Snížení: distance rychleji zhasíná → pozdější/klidnější alerty.',
  },
  plateauMax: {
    title: 'Distance plateau (max při přibližování)',
    unit: '',
    step: 0.01,
    group: 'Approach',
    info: 'Maximální plateau při výrazném přibližování (vyšší approachSpeed).\n\n↑ Zvýšení: při rychlém dojíždění bude distance score agresivnější → dřívější alerty.\n↓ Snížení: i při dojíždění bude distance tlumená → méně citlivé.',
  },
  approachGateMin: {
    title: 'Min approachSpeed pro navýšení plateaua',
    unit: 'm/s',
    step: 0.05,
    group: 'Approach',
    info: 'Pod touto relativní rychlostí (approachSpeed) se plateau nezvyšuje a zůstává na base.\n\n↑ Zvýšení: více scén se bere jako „nepřibližuje“ → méně/pozdější alerty.\n↓ Snížení: plateau se začne zvyšovat dřív → více/dřívější alerty.',
  },
  approachLow: {
    title: 'ApproachLow',
    unit: 'm/s',
    step: 0.1,
    group: 'Approach',
    info: 'Spodní bod škálování: od této relativní rychlosti se začíná plynule zvedat plateau směrem k max.\n\n↑ Zvýšení: potřebuje větší dojíždění pro zvýšení plateaua → méně citlivé.\n↓ Snížení: zvýšení plateaua už při menším dojíždění → dřívější alerty.',
  },
  approachHigh: {
    title: 'ApproachHigh',
    unit: 'm/s',
    step: 0.1,
    group: 'Approach',
    info: 'Horní bod škálování: od této relativní rychlosti už je plateau na max.\n\n↑ Zvýšení: max plateau až při vyšší rychlosti dojíždění → méně agresivní ve středních rychlostech.\n↓ Snížení: max plateau dřív → agresivnější/dřívější alerty.',
  },
};

function validateCustomParams(p: CustomEngineParams): FieldErrors {
  const e: FieldErrors = {};
  const finite = (k: keyof CustomEngineParams) => { if (!Number.isFinite(p[k])) e[k] = 'Must be finite.'; };
  (Object.keys(p) as Array<keyof CustomEngineParams>).forEach(finite);
  if (p.smoothTtcAlphaDrop < 0 || p.smoothTtcAlphaDrop > 1) e.smoothTtcAlphaDrop = 'Range 0..1';
  if (p.smoothTtcMaxDropRate <= 0) e.smoothTtcMaxDropRate = 'Must be > 0';
  if (p.ttcInvalidHoldMs < 0 || p.ttcInvalidHoldMs > 5000) e.ttcInvalidHoldMs = 'Range 0..5000ms';
  if (p.ttcHeightHoldMs < 0 || p.ttcHeightHoldMs > 5000) e.ttcHeightHoldMs = 'Range 0..5000ms';
  if (p.minGrowthRatio <= 0) e.minGrowthRatio = 'Must be > 0';
  if (p.minDeltaHPx <= 0) e.minDeltaHPx = 'Must be > 0';
  if (p.ttcFromDistApproachGate <= 0) e.ttcFromDistApproachGate = 'Must be > 0';
  if (p.plateauBase < 0 || p.plateauBase > 1) e.plateauBase = 'Range 0..1';
  if (p.plateauMax < 0 || p.plateauMax > 1) e.plateauMax = 'Range 0..1';
  if (p.plateauMax < p.plateauBase) e.plateauMax = 'Must be >= plateauBase';
  if (p.approachHigh <= p.approachLow) e.approachHigh = 'Must be > approachLow';
  return e;
}

function smoothTtcCustom(ttcRaw: number, tsMs: number, cfg: CustomEngineParams, state: TtcTuneState): number {
  const raw = Number.isFinite(ttcRaw) && ttcRaw > 0 ? clamp(ttcRaw, 0.05, 120) : Number.POSITIVE_INFINITY;
  if (!Number.isFinite(raw)) {
    if (state.ttcEmaValid && Number.isFinite(state.ttcEma) && state.lastTtcFiniteTsMs > 0 && (tsMs - state.lastTtcFiniteTsMs) <= cfg.ttcInvalidHoldMs) {
      return state.ttcEma;
    }
    state.ttcEmaValid = false;
    state.ttcEma = Number.POSITIVE_INFINITY;
    state.lastTsMs = tsMs;
    return Number.POSITIVE_INFINITY;
  }

  state.lastTtcFiniteTsMs = tsMs;
  if (!state.ttcEmaValid || !Number.isFinite(state.ttcEma) || state.lastTsMs <= 0) {
    state.ttcEma = raw;
    state.ttcEmaValid = true;
    state.lastTsMs = tsMs;
    return state.ttcEma;
  }

  const dtSec = Math.max(0.001, (tsMs - state.lastTsMs) / 1000);
  state.lastTsMs = tsMs;
  const maxDrop = cfg.smoothTtcMaxDropRate * dtSec;
  const maxRise = 3.0 * dtSec;
  const prev = state.ttcEma;
  const clamped = raw < prev ? Math.max(raw, prev - maxDrop) : Math.min(raw, prev + maxRise);
  const alpha = clamped < prev ? cfg.smoothTtcAlphaDrop : 0.20;
  state.ttcEma = prev + alpha * (clamped - prev);
  return state.ttcEma;
}

function applyCustomTtcPipeline(
  input: FrameIn,
  relApproachMps: number,
  tsMs: number,
  cfg: CustomEngineParams,
  state: TtcTuneState,
): number {
  const rawHeight = Number(input.ttcHeightSec ?? input.ttcSec);
  const rawDistDirect = Number(input.distanceM) / Math.max(relApproachMps, 1e-6);
  const rawDist = (Number.isFinite(rawDistDirect) && relApproachMps > cfg.ttcFromDistApproachGate)
    ? clamp(rawDistDirect, 0.05, 120)
    : Number.POSITIVE_INFINITY;

  let heightNow = Number.isFinite(rawHeight) && rawHeight > 0 ? clamp(rawHeight, 0.05, 120) : Number.NaN;
  if (Number.isFinite(heightNow) && Number.isFinite(state.prevHeightTtc)) {
    const drop = state.prevHeightTtc - heightNow;
    const ratio = state.prevHeightTtc / Math.max(heightNow, 0.05);
    const minDropSec = cfg.minDeltaHPx * 0.10;
    if (!(ratio >= cfg.minGrowthRatio && drop >= minDropSec)) {
      heightNow = Number.NaN;
    }
  }
  if (Number.isFinite(heightNow)) {
    state.ttcHeightHeld = heightNow;
    state.ttcHeightHeldTsMs = tsMs;
    state.prevHeightTtc = heightNow;
  }
  const heldHeight = (Number.isFinite(heightNow) || (state.ttcHeightHeldTsMs > 0 && (tsMs - state.ttcHeightHeldTsMs) <= cfg.ttcHeightHoldMs))
    ? state.ttcHeightHeld
    : Number.NaN;

  const fused = fuseTtc(
    Number.isFinite(heldHeight) ? heldHeight : undefined,
    Number.isFinite(rawDist) ? rawDist : undefined,
    Number(input.distanceM),
    relApproachMps,
    Boolean(input.bottomOccluded),
    false,
    Number(input.qualityWeight ?? 1),
  );
  return smoothTtcCustom(fused.ttcFused, tsMs, cfg, state);
}

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
  // Legacy formats:
  // - {type:"FRAME", tSec, in:{...}, out:{...}}
  // - {in:{...}, out:{...}}
  // Newer log_analyzer export (mcaw.frames.v1):
  // - {tMs, distanceM, approachSpeedMps, ttcSec, ...} (flat)
  // - {type:"META", ...} should be ignored
  if (!x) return false;
  if (x.type === 'META') return false;
  if (x.type === 'FRAME' || (x.in && x.out) || (x.in && x.tSec !== undefined)) return true;
  // Accept flat mcaw.frames.v1 frames
  if (x.tMs !== undefined && (x.distanceM !== undefined || x.distM !== undefined) && x.ttcSec !== undefined) return true;
  return false;
}

function toFiniteOrUndef(v: any): number | undefined {
  const n = typeof v === 'number' ? v : typeof v === 'string' ? Number(v) : NaN;
  return Number.isFinite(n) ? n : undefined;
}

function normalizeFrameEvent(it: any, base: string): { id: string; tSec: number; input: FrameIn; out?: FrameOut } | null {
  // Legacy
  if (it.type === 'FRAME' || it.in) {
    const tSec = Number(it.tSec ?? it.t ?? 0);
    const input: FrameIn = it.in ?? it.input ?? {};
    const out: FrameOut | undefined = it.out ?? it.output ?? it.outKotlin;
    const scenarioFromLine = String(it.scenario ?? it.scenarioId ?? '').trim();
    const id = scenarioFromLine.length > 0 ? scenarioFromLine : base;
    return { id, tSec, input, out };
  }

  // Flat mcaw.frames.v1 from log_analyzer
  const tMs = toFiniteOrUndef(it.tMs);
  if (tMs === undefined) return null;
  const tSec = tMs / 1000.0;

  const idFromLine = String(it.scenario ?? it.scenarioId ?? it.source ?? '').trim();
  const id = idFromLine.length > 0 ? idFromLine : base;

  const input: FrameIn = {
    effectiveMode: toFiniteOrUndef(it.effectiveMode) ?? 1,
    distanceM: toFiniteOrUndef(it.distanceM) ?? toFiniteOrUndef(it.distM) ?? 0,
    approachSpeedMps: toFiniteOrUndef(it.approachSpeedMps) ?? toFiniteOrUndef(it.approachSpeed) ?? toFiniteOrUndef(it.relV) ?? 0,
    ttcSec: toFiniteOrUndef(it.ttcSec) ?? toFiniteOrUndef(it.ttc) ?? 0,
    ttcHeightSec: toFiniteOrUndef(it.ttcHeightSec),
    ttcDistSec: toFiniteOrUndef(it.ttcDistSec),
    ttcSlopeSecPerSec: toFiniteOrUndef(it.ttcSlopeSecPerSec) ?? 0,
    relDerivValid: it.relDerivValid == null ? undefined : Boolean(it.relDerivValid),
    relSignedSampleMps: toFiniteOrUndef(it.relSignedSampleMps),
    boxHeightPx: toFiniteOrUndef(it.boxHeightPx),
    trackedPresent: it.trackedPresent == null ? undefined : Boolean(it.trackedPresent),
    occlConfirmed: it.occlConfirmed == null ? undefined : Boolean(it.occlConfirmed),
    roiContainment: toFiniteOrUndef(it.roiContainment) ?? toFiniteOrUndef(it.roi) ?? 1,
    qualityWeight: toFiniteOrUndef(it.qualityWeight) ?? toFiniteOrUndef(it.quality) ?? 1,
    brakeCueActive: Boolean(it.brakeCueActive ?? it.brakeCue ?? false),
    brakeCueStrength: toFiniteOrUndef(it.brakeCueStrength) ?? (Boolean(it.brakeCueActive ?? it.brakeCue) ? 1 : 0),
    occlusionCloseFactor: toFiniteOrUndef(it.occlusionCloseFactor) ?? 0,
    occlusionCloseEligible: Boolean(it.occlusionCloseEligible ?? false),
    // Optional / newer
    distanceConfidence: toFiniteOrUndef(it.distanceConfidence),
    egoBrakingConfidence: toFiniteOrUndef(it.egoBrakingConfidence),
    riderSpeedMps: toFiniteOrUndef(it.riderSpeedMps),
    riderSpeedConfidence: toFiniteOrUndef(it.riderSpeedConfidence),
    leanDeg: it.leanDeg ?? null,
    bottomOccluded: it.bottomOccluded == null ? undefined : Boolean(it.bottomOccluded),
    suppressRecedingHard: it.suppressRecedingHard == null ? undefined : Boolean(it.suppressRecedingHard),
    suppressSteadyGapHard: it.suppressSteadyGapHard == null ? undefined : Boolean(it.suppressSteadyGapHard),
  } as any;
  (input as any).relSignedEmaMps = toFiniteOrUndef(it.relSignedEmaMps);

  const out: FrameOut | undefined =
    it.level !== undefined || it.riskScore !== undefined || it.reasonBits !== undefined
      ? ({
          level: toFiniteOrUndef(it.level) ?? 0,
          riskScore: toFiniteOrUndef(it.riskScore) ?? 0,
          reasonBits: toFiniteOrUndef(it.reasonBits) ?? 0,
          rawRisk: toFiniteOrUndef(it.rawRisk),
          emaRisk: toFiniteOrUndef(it.emaRisk),
        } as any)
      : undefined;

  return { id, tSec, input, out };
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
        const norm = normalizeFrameEvent(it, base);
        if (!norm) continue;
        const { id, tSec, input, out } = norm;
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
  const [useCustomParams, setUseCustomParams] = useState<boolean>(false);
  const [customParamsDraft, setCustomParamsDraft] = useState<CustomEngineParams>(CUSTOM_DEFAULTS);
  const [customParams, setCustomParams] = useState<CustomEngineParams>(CUSTOM_DEFAULTS);
  const [customErrors, setCustomErrors] = useState<FieldErrors>({});

  useEffect(() => {
    if (!useCustomParams) return;
    const handle = window.setTimeout(() => {
      const errs = validateCustomParams(customParamsDraft);
      setCustomErrors(errs);
      if (Object.keys(errs).length === 0) setCustomParams(customParamsDraft);
    }, 200);
    return () => window.clearTimeout(handle);
  }, [useCustomParams, customParamsDraft]);
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

    const engDefault = new RiskEngineRef();
    const engCustom = new RiskEngineRef();

    const t: number[] = [];
    const defaultRisk: number[] = [];
    const defaultLevel: number[] = [];
    const defaultReason: number[] = [];
    const defaultRaw: number[] = [];
    const defaultEma: number[] = [];

    const referenceRisk: number[] = [];
    const referenceLevel: number[] = [];
    const referenceReason: number[] = [];
    const referenceRaw: number[] = [];
    const referenceEma: number[] = [];

    const simulatedRisk: number[] = [];
    const simulatedLevel: number[] = [];
    const simulatedReason: number[] = [];
    const simulatedRaw: number[] = [];
    const simulatedEma: number[] = [];
    const simulatedDistOrange: number[] = [];
    const simulatedDistRed: number[] = [];

    const relDerivedMps: number[] = [];
    const relDerivedValid: boolean[] = [];

    let refThrFull: any = null;
    let mismatch = 0;
    const hasReferenceOut = dataSource === 'upload' && frames.some((f) => !!f.outKotlin);

    const relState = new RelStabilityState();
    const customTtcState: TtcTuneState = {
      lastTsMs: -1,
      ttcHeightHeld: Number.NaN,
      ttcHeightHeldTsMs: -1,
      ttcEma: Number.POSITIVE_INFINITY,
      ttcEmaValid: false,
      lastTtcFiniteTsMs: -1,
      prevHeightTtc: Number.NaN,
    };

    for (const fr of frames) {
      const input = fr.in;
      const tsMs = Math.round(fr.tSec * 1000);
      const relFrame = relState.step({
        tsMs,
        distanceM: Number(input.distanceM),
        hasBest: Boolean(input.hasBest ?? true),
        bestId: input.bestId === undefined ? undefined : Number(input.bestId),
        bottomOccluded: input.bottomOccluded,
        riderSpeedKnown: Number.isFinite(Number(input.riderSpeedMps)),
      });

      const relEma = Number((input as any).relSignedEmaMps);
      const relValidFromInput = (input as any).relDerivValid == null ? undefined : Boolean((input as any).relDerivValid);
      relDerivedMps.push(Number.isFinite(relEma) ? relEma : Number(relFrame.relSignedEmaMps));
      relDerivedValid.push(relValidFromInput ?? Boolean(relFrame.relDerivValid));

      const approachForRisk = Number((relValidFromInput ?? relFrame.relDerivValid)
        ? Math.abs(Number.isFinite(relEma) ? relEma : relFrame.relSignedEmaMps)
        : Number(input.approachSpeedMps ?? 0));
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
        suppressRecedingHard: (input as any).suppressRecedingHard ?? (relFrame.trendState === 2),
        suppressSteadyGapHard: (input as any).suppressSteadyGapHard ?? relFrame.steadySuppressActive,
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

      const defaultOut = (engDefault as any).evaluate(evalInput);
      const effectiveMode = Number(evalInput.effectiveMode ?? 1);
      const qualityWeight = Number(evalInput.qualityWeight ?? 1);
      refThrFull = refThrFull ?? (engDefault as any).debugDerivedThresholds(effectiveMode, qualityWeight);

      t.push(fr.tSec);
      defaultRisk.push(Number(defaultOut.riskScore ?? 0));
      defaultLevel.push(Number(defaultOut.level ?? 0));
      defaultReason.push(Number(defaultOut.reasonBits ?? 0));
      defaultRaw.push(Number(defaultOut.rawRisk ?? defaultOut.riskScore ?? 0));
      defaultEma.push(Number(defaultOut.emaRisk ?? defaultOut.riskScore ?? 0));

      const refOut = fr.outKotlin ?? defaultOut;
      referenceRisk.push(Number(refOut.riskScore ?? 0));
      referenceLevel.push(Number(refOut.level ?? 0));
      referenceReason.push(Number(refOut.reasonBits ?? 0));
      referenceRaw.push(Number((refOut as any).rawRisk ?? refOut.riskScore ?? 0));
      referenceEma.push(Number((refOut as any).emaRisk ?? refOut.riskScore ?? 0));

      if (fr.outKotlin) {
        if (Number(fr.outKotlin.level) !== Number(defaultOut.level) || Number(fr.outKotlin.reasonBits) !== Number(defaultOut.reasonBits)) {
          mismatch++;
        }
      }

      let altOut: any = null;
      let distO = Number.NaN;
      let distR = Number.NaN;
      if (useCustomParams) {
        const tunedInput: FrameIn = { ...evalInput };
        tunedInput.ttcSec = applyCustomTtcPipeline(tunedInput, approachForRisk, tsMs, customParams, customTtcState);

        if (whatIf.dynamicDistanceEnabled) {
          const v = Number(evalInput.riderSpeedMps ?? 0);
          if (Number.isFinite(v) && v > 0.1) {
            const thr = (engCustom as any).debugDerivedThresholds(effectiveMode, qualityWeight, undefined, {
              dynamicDistanceEnabled: whatIf.dynamicDistanceEnabled,
              dynamicDistanceOrangeSec: whatIf.orangeGapSec,
              dynamicDistanceRedSec: whatIf.redGapSec,
              plateauBase: customParams.plateauBase,
              plateauMax: customParams.plateauMax,
              approachGateMin: customParams.approachGateMin,
              approachLow: customParams.approachLow,
              approachHigh: customParams.approachHigh,
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
            altOut = (engCustom as any).evaluate(tunedInput, override, {
              dynamicDistanceEnabled: whatIf.dynamicDistanceEnabled,
              dynamicDistanceOrangeSec: whatIf.orangeGapSec,
              dynamicDistanceRedSec: whatIf.redGapSec,
              plateauBase: customParams.plateauBase,
              plateauMax: customParams.plateauMax,
              approachGateMin: customParams.approachGateMin,
              approachLow: customParams.approachLow,
              approachHigh: customParams.approachHigh,
            });
          } else {
            altOut = (engCustom as any).evaluate(tunedInput, undefined, {
              dynamicDistanceEnabled: whatIf.dynamicDistanceEnabled,
              dynamicDistanceOrangeSec: whatIf.orangeGapSec,
              dynamicDistanceRedSec: whatIf.redGapSec,
              plateauBase: customParams.plateauBase,
              plateauMax: customParams.plateauMax,
              approachGateMin: customParams.approachGateMin,
              approachLow: customParams.approachLow,
              approachHigh: customParams.approachHigh,
            });
          }
        } else {
          altOut = (engCustom as any).evaluate(tunedInput, undefined, {
            dynamicDistanceEnabled: whatIf.dynamicDistanceEnabled,
            dynamicDistanceOrangeSec: whatIf.orangeGapSec,
            dynamicDistanceRedSec: whatIf.redGapSec,
          });
        }
      } else if (hasReferenceOut) {
        altOut = defaultOut;
      }

      if (altOut) {
        simulatedRisk.push(Number(altOut.riskScore ?? 0));
        simulatedLevel.push(Number(altOut.level ?? 0));
        simulatedReason.push(Number(altOut.reasonBits ?? 0));
        simulatedRaw.push(Number(altOut.rawRisk ?? altOut.riskScore ?? 0));
        simulatedEma.push(Number(altOut.emaRisk ?? altOut.riskScore ?? 0));
        simulatedDistOrange.push(distO);
        simulatedDistRed.push(distR);
      }
    }

    const thresholds: Thresholds = {
      ttcOrange: Number(refThrFull?.ttcOrange ?? 3),
      ttcRed: Number(refThrFull?.ttcRed ?? 2),
      distOrangeM: Number(refThrFull?.distOrange ?? 15),
      distRedM: Number(refThrFull?.distRed ?? 8),
      relOrange: Number(refThrFull?.relOrange ?? 3),
      relRed: Number(refThrFull?.relRed ?? 5),
      orangeOn: Number(refThrFull?.orangeOn ?? 0.45),
      orangeOff: Number(refThrFull?.orangeOff ?? 0.39),
      redOn: Number(refThrFull?.redOn ?? 0.75),
      redOff: Number(refThrFull?.redOff ?? 0.7),
    };

    const referenceTimes = computeFirstTimes(referenceLevel, t);
    const simulatedTimes = simulatedLevel.length > 0 ? computeFirstTimes(simulatedLevel, t) : { firstOrange: null, firstRed: null };
    const hasComparison = simulatedLevel.length > 0;

    const speedKmh = frames.map((f) => mpsToKmh(Number(f.in.riderSpeedMps ?? 0)));
    const distM = frames.map((f) => Number(f.in.distanceM));
    const relMps = relDerivedMps.map((v, i) => (relDerivedValid[i] ? v : Number.NaN));
    const ttcSec = frames.map((f) => Number(f.in.ttcSec));

    const rows: TableRow[] = frames.map((f, i) => ({
      tSec: f.tSec,
      distanceM: Number(f.in.distanceM),
      relMps: relDerivedValid[i] ? Number(relDerivedMps[i]) : Number.NaN,
      relSignedSampleMps: Number((f.in as any).relSignedSampleMps),
      relDerivValid: Boolean((f.in as any).relDerivValid ?? relDerivedValid[i]),
      relSignedEmaMps: Number((f.in as any).relSignedEmaMps),
      ttcSec: Number(f.in.ttcSec),
      ttcHeightSec: Number((f.in as any).ttcHeightSec),
      ttcDistSec: Number((f.in as any).ttcDistSec),
      boxHeightPx: Number((f.in as any).boxHeightPx),
      trackedPresent: (f.in as any).trackedPresent == null ? undefined : Boolean((f.in as any).trackedPresent),
      bottomOccluded: (f.in as any).bottomOccluded == null ? undefined : Boolean((f.in as any).bottomOccluded),
      occlConfirmed: (f.in as any).occlConfirmed == null ? undefined : Boolean((f.in as any).occlConfirmed),
      suppressRecedingHard: (f.in as any).suppressRecedingHard == null ? undefined : Boolean((f.in as any).suppressRecedingHard),
      suppressSteadyGapHard: (f.in as any).suppressSteadyGapHard == null ? undefined : Boolean((f.in as any).suppressSteadyGapHard),
      speedKmh: mpsToKmh(Number(f.in.riderSpeedMps ?? 0)),
      referenceRisk: referenceRisk[i],
      referenceLevel: referenceLevel[i],
      referenceReasonBits: referenceReason[i],
      simulatedRisk: hasComparison ? simulatedRisk[i] : undefined,
      simulatedLevel: hasComparison ? simulatedLevel[i] : undefined,
      simulatedReasonBits: hasComparison ? simulatedReason[i] : undefined,
      distOrangeM: hasComparison ? simulatedDistOrange[i] : undefined,
      distRedM: hasComparison ? simulatedDistRed[i] : undefined,
    }));

    const csvRows = rows.map((r) => ({
      tSec: round3(r.tSec),
      distanceM: round3(r.distanceM),
      relMps: round3(r.relMps),
      relSignedSampleMps: r.relSignedSampleMps == null || !Number.isFinite(r.relSignedSampleMps as number) ? '' : round3(r.relSignedSampleMps as number),
      relDerivValid: r.relDerivValid == null ? '' : r.relDerivValid,
      relSignedEmaMps: r.relSignedEmaMps == null || !Number.isFinite(r.relSignedEmaMps as number) ? '' : round3(r.relSignedEmaMps as number),
      ttcSec: Number.isFinite(r.ttcSec) ? round3(r.ttcSec) : '',
      ttcHeightSec: r.ttcHeightSec == null || !Number.isFinite(r.ttcHeightSec as number) ? '' : round3(r.ttcHeightSec as number),
      ttcDistSec: r.ttcDistSec == null || !Number.isFinite(r.ttcDistSec as number) ? '' : round3(r.ttcDistSec as number),
      boxHeightPx: r.boxHeightPx == null || !Number.isFinite(r.boxHeightPx as number) ? '' : round3(r.boxHeightPx as number),
      trackedPresent: r.trackedPresent == null ? '' : r.trackedPresent,
      bottomOccluded: r.bottomOccluded == null ? '' : r.bottomOccluded,
      occlConfirmed: r.occlConfirmed == null ? '' : r.occlConfirmed,
      suppressRecedingHard: r.suppressRecedingHard == null ? '' : r.suppressRecedingHard,
      suppressSteadyGapHard: r.suppressSteadyGapHard == null ? '' : r.suppressSteadyGapHard,
      speedKmh: round3(r.speedKmh),
      referenceRisk: round3(r.referenceRisk),
      referenceLevel: r.referenceLevel,
      referenceReasonBits: r.referenceReasonBits,
      simulatedRisk: r.simulatedRisk == null ? '' : round3(r.simulatedRisk),
      simulatedLevel: r.simulatedLevel ?? '',
      simulatedReasonBits: r.simulatedReasonBits ?? '',
      distOrangeM: r.distOrangeM == null || !Number.isFinite(r.distOrangeM) ? '' : round3(r.distOrangeM),
      distRedM: r.distRedM == null || !Number.isFinite(r.distRedM) ? '' : round3(r.distRedM),
    }));

    return {
      t,
      reference: { risk: referenceRisk, level: referenceLevel, reason: referenceReason, raw: referenceRaw, ema: referenceEma, first: referenceTimes },
      simulated: hasComparison ? { risk: simulatedRisk, level: simulatedLevel, reason: simulatedReason, raw: simulatedRaw, ema: simulatedEma, first: simulatedTimes, distOrange: simulatedDistOrange, distRed: simulatedDistRed } : null,
      thresholds,
      mismatchCount: hasReferenceOut ? mismatch : null,
      hasReferenceOut,
      hasComparison,
      input: { speedKmh, distM, relMps, ttcSec },
      rows,
      csvRows,
    };
  }, [activeFrames, customParams, dataSource, useCustomParams, whatIf]);

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
      <div style={{ marginTop: -6, marginBottom: 10, color: '#4b5563' }}>Ladicí UI pro scénáře a tuning prahů/filtrů. Info bubliny vysvětlují dopad na ORANGE/RED.</div>
      <div style={{ marginTop: -8, marginBottom: 10 }}>
        <span style={{ display: 'inline-block', background: '#111827', color: '#fff', padding: '4px 8px', borderRadius: 8, fontSize: 12, fontWeight: 700 }}>
          UI MARKER 2026-02-24
        </span>
      </div>

      <div style={{ display: 'flex', gap: 12, alignItems: 'center', flexWrap: 'wrap', marginBottom: 12 }}>
        <label style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
          <span style={{ display: 'inline-flex', gap: 6, alignItems: 'center' }}>
            <span>Zdroj dat</span>
            <InfoTip text="Vyber, odkud se berou vstupní framy: buď reálný log (frames.jsonl), nebo interní Scenario builder." />
          </span>
          <select value={dataSource} onChange={(e) => setDataSource(e.target.value as DataSource)}>
            <option value="upload">Upload frames.jsonl</option>
            <option value="builder">Scenario builder</option>
          </select>
        </label>

        <label style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
          <span style={{ display: 'inline-flex', gap: 6, alignItems: 'center' }}>
            <span>Použít vlastní parametry</span>
            <InfoTip text="Zapne ladicí (CUSTOM) režim: v Results uvidíš porovnání BASE vs. TUNED. Mění pouze výpočet v simulátoru, ne scénář." />
          </span>
          <input type="checkbox" checked={useCustomParams} onChange={(e) => setUseCustomParams(e.target.checked)} />
        </label>

        <label style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
          <span style={{ display: 'inline-flex', gap: 6, alignItems: 'center' }}>
            <span>Dynamická vzdálenost (time-gap)</span>
            <InfoTip text="Když je zapnuto, distance threshold pro ORANGE/RED se odvozuje z rychlosti: distance ≈ v * gap. Vyšší gap = dřívější alerty." />
          </span>
          <input
            type="checkbox"
            checked={whatIf.dynamicDistanceEnabled}
            onChange={(e) => setWhatIf({ ...whatIf, dynamicDistanceEnabled: e.target.checked })}
            disabled={!useCustomParams}
          />
        </label>

        <label style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
          <span style={{ display: 'inline-flex', gap: 6, alignItems: 'center' }}>
            <span>ORANGE time-gap (s)</span>
            <InfoTip text="Použije se jen když je zapnutá „Dynamická vzdálenost“.\n\n↑ Zvýšení: ORANGE distance = v * gap bude větší → ORANGE dřív.\n↓ Snížení: ORANGE později." />
          </span>
          <input type="number" step={0.1} value={whatIf.orangeGapSec} onChange={(e) => setWhatIf({ ...whatIf, orangeGapSec: Number(e.target.value) })} style={{ width: 70 }} disabled={!useCustomParams} />
        </label>
        <label style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
          <span style={{ display: 'inline-flex', gap: 6, alignItems: 'center' }}>
            <span>RED time-gap (s)</span>
            <InfoTip text="Použije se jen když je zapnutá „Dynamická vzdálenost“.\n\n↑ Zvýšení: RED distance = v * gap bude větší → RED dřív (agresivnější).\n↓ Snížení: RED později." />
          </span>
          <input type="number" step={0.1} value={whatIf.redGapSec} onChange={(e) => setWhatIf({ ...whatIf, redGapSec: Number(e.target.value) })} style={{ width: 70 }} disabled={!useCustomParams} />
        </label>

        <label style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
          <span style={{ display: 'inline-flex', gap: 6, alignItems: 'center' }}>
            <span>ORANGE clamp (m)</span>
            <InfoTip text="Min..max omezení pro dynamickou ORANGE vzdálenost (v*gap).\n\n↑ Min: ORANGE nikdy nebude blíž než tato vzdálenost → ORANGE dřív.\n↓ Min: ORANGE může být blíž → ORANGE později.\n↑ Max: ve vysoké rychlosti může ORANGE růst víc → ORANGE dřív.\n↓ Max: ORANGE se omezí shora → méně agresivní při vysoké rychlosti." />
          </span>
          <input type="number" value={whatIf.distOrangeClampMinM} onChange={(e) => setWhatIf({ ...whatIf, distOrangeClampMinM: Number(e.target.value) })} style={{ width: 70 }} disabled={!useCustomParams} />
          <span>..</span>
          <input type="number" value={whatIf.distOrangeClampMaxM} onChange={(e) => setWhatIf({ ...whatIf, distOrangeClampMaxM: Number(e.target.value) })} style={{ width: 70 }} disabled={!useCustomParams} />
        </label>

        <label style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
          <span style={{ display: 'inline-flex', gap: 6, alignItems: 'center' }}>
            <span>RED clamp (m)</span>
            <InfoTip text="Min..max omezení pro dynamickou RED vzdálenost (v*gap).\n\n↑ Min: RED nikdy nebude blíž než tato vzdálenost → RED dřív (agresivnější).\n↓ Min: RED může být blíž → RED později.\n↑ Max: ve vysoké rychlosti může RED růst víc → RED dřív.\n↓ Max: RED se omezí shora → méně agresivní při vysoké rychlosti." />
          </span>
          <input type="number" value={whatIf.distRedClampMinM} onChange={(e) => setWhatIf({ ...whatIf, distRedClampMinM: Number(e.target.value) })} style={{ width: 70 }} disabled={!useCustomParams} />
          <span>..</span>
          <input type="number" value={whatIf.distRedClampMaxM} onChange={(e) => setWhatIf({ ...whatIf, distRedClampMaxM: Number(e.target.value) })} style={{ width: 70 }} disabled={!useCustomParams} />
        </label>


        {useCustomParams && (
          <div style={{ border: '1px solid #d1d5db', borderRadius: 12, padding: 12, width: '100%', background: '#f9fafb' }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 6, alignItems: 'center' }}>
              <div style={{ display: 'flex', gap: 10, alignItems: 'center' }}>
                <b>Nastavení enginu (tuning)</b>
                <span style={{ background: '#7c3aed', color: 'white', borderRadius: 12, padding: '2px 8px', fontSize: 11 }}>CUSTOM</span>
              </div>
              <span style={{ fontSize: 12, color: '#6b7280' }}>týká se jen výpočtu TUNED</span>
            </div>

            <p style={{ marginTop: 0, marginBottom: 10, color: '#4b5563' }}>
              Parametry jsou technické. Upravuj je pro ladění stability a načasování alertů (ORANGE/RED). Doporučení: měň po malých krocích a porovnávej BASE vs. TUNED.
            </p>

            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(2, minmax(320px, 1fr))', gap: 12 }}>
              <div style={{ background: 'white', border: '1px solid #e5e7eb', borderRadius: 12, padding: 10 }}>
                <div style={{ fontWeight: 700, marginBottom: 8 }}>TTC stabilizace</div>
                <div style={{ display: 'grid', gap: 8 }}>
                  {(Object.keys(customParamsDraft) as Array<keyof CustomEngineParams>)
                    .filter((k) => PARAM_META[k].group === 'TTC')
                    .map((k) => (
                      <div key={k} style={{ display: 'grid', gridTemplateColumns: '1fr 110px', gap: 10, alignItems: 'center' }}>
                        <LabelWithInfo label={<span>{PARAM_META[k].title}{PARAM_META[k].unit ? ` (${PARAM_META[k].unit})` : ''}</span>} info={PARAM_META[k].info} />
                        <div style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
                          <input
                            type="number"
                            step={PARAM_META[k].step}
                            value={customParamsDraft[k]}
                            onChange={(e) => setCustomParamsDraft({ ...customParamsDraft, [k]: Number(e.target.value) })}
                            style={{ width: '100%' }}
                          />
                          {customErrors[k] && <span style={{ color: '#dc2626', fontSize: 11 }}>{customErrors[k]}</span>}
                        </div>
                      </div>
                    ))}
                </div>
              </div>

              <div style={{ background: 'white', border: '1px solid #e5e7eb', borderRadius: 12, padding: 10 }}>
                <div style={{ fontWeight: 700, marginBottom: 8 }}>Přibližování a distance plateau</div>
                <div style={{ display: 'grid', gap: 8 }}>
                  {(Object.keys(customParamsDraft) as Array<keyof CustomEngineParams>)
                    .filter((k) => PARAM_META[k].group === 'Approach')
                    .map((k) => (
                      <div key={k} style={{ display: 'grid', gridTemplateColumns: '1fr 110px', gap: 10, alignItems: 'center' }}>
                        <LabelWithInfo label={<span>{PARAM_META[k].title}{PARAM_META[k].unit ? ` (${PARAM_META[k].unit})` : ''}</span>} info={PARAM_META[k].info} />
                        <div style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
                          <input
                            type="number"
                            step={PARAM_META[k].step}
                            value={customParamsDraft[k]}
                            onChange={(e) => setCustomParamsDraft({ ...customParamsDraft, [k]: Number(e.target.value) })}
                            style={{ width: '100%' }}
                          />
                          {customErrors[k] && <span style={{ color: '#dc2626', fontSize: 11 }}>{customErrors[k]}</span>}
                        </div>
                      </div>
                    ))}
                </div>
              </div>
            </div>
          </div>
        )}

        <button onClick={downloadCsv} disabled={!simulation || (useCustomParams && Object.keys(customErrors).length > 0)}>Download CSV</button>
      </div>

      <section style={{ marginTop: 10 }}><h3>Inputs</h3><p style={{ marginTop: 0, color: "#4b5563" }}>Scenario source from uploaded log frames or manual builder input.</p>{dataSource === 'upload' ? (
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
      </section>

      <section style={{ marginTop: 12 }}><h3>Results</h3><p style={{ marginTop: 0, color: "#4b5563" }}>Reference timeline from Kotlin / CI when available, plus simulator recompute for parity or custom what-if.</p>{simulation && (
        <>
          <SummaryCards
            referenceFirstOrange={simulation.reference.first.firstOrange}
            referenceFirstRed={simulation.reference.first.firstRed}
            simulatedFirstOrange={simulation.simulated?.first.firstOrange ?? null}
            simulatedFirstRed={simulation.simulated?.first.firstRed ?? null}
            mismatchCount={simulation.mismatchCount}
            hasReferenceOut={simulation.hasReferenceOut}
            hasSimulated={!!simulation.simulated}
          />

          <PlotsPanel
            t={simulation.t}
            baseEma={simulation.reference.ema}
            tunedEma={simulation.simulated?.ema}
            baseRaw={simulation.reference.raw}
            tunedRaw={simulation.simulated?.raw}
            baseLevel={simulation.reference.level}
            tunedLevel={simulation.simulated?.level}
            thresholds={simulation.thresholds}
            baseFirstOrange={simulation.reference.first.firstOrange}
            baseFirstRed={simulation.reference.first.firstRed}
            tunedFirstOrange={simulation.simulated?.first.firstOrange ?? null}
            tunedFirstRed={simulation.simulated?.first.firstRed ?? null}
            showInputPlot={showInputPlot}
            inputSeries={inputSeries}
            onToggleInputPlot={setShowInputPlot}
            onToggleInputSeries={(name, enabled) => setInputEnabled({ ...inputEnabled, [name]: enabled })}
          />

          <ResultsTable
            rows={simulation.rows}
            showOnlyDiffs={showOnlyDiffs}
            onToggleOnlyDiffs={setShowOnlyDiffs}
            hasComparison={simulation.hasComparison}
          />
        </>
      )}

      </section>

      <section style={{ marginTop: 12 }}><h3>Details</h3><p style={{ marginTop: 0, color: "#4b5563" }}>Debug tables and optional traces for verification.</p>
      {!simulation && (
        <div style={{ marginTop: 18, color: '#666' }}>
          Upload a <code>*.frames.jsonl</code> file or create a scenario in builder and click Recompute.
        </div>
      )}
      </section>
    </div>
  );
}


export default App;

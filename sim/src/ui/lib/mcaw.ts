import { Profile, evalProfile } from './profiles';
import { ScenarioDraft } from './scenario';
import { kmhToMps, round3 } from './utils';

export type Domain = 'CITY' | 'TUNNEL' | 'HIGHWAY' | 'RURAL';
export type Vehicle = 'CAR' | 'MOTO';

export type McawProfile =
  | { kind: 'hold'; value: number | boolean | null }
  | { kind: 'linear'; from: number; to: number }
  | { kind: 'derived'; eps?: number };

export type McawConfig = {
  effectiveMode: number;
  hz: number;
  riderSpeedMps: number;
  qualityWeight: number;
  roiContainment: number;
  egoOffsetN: number;
  leanDeg: number | null;
};

export type McawExpectation =
  | { type: 'MustEnterLevelBy'; level: number; latestSecAfterHazard: number; hazardTimeSec: number; message: string }
  | { type: 'MustNotEnterLevel'; level: number; message: string }
  | { type: 'MaxTransitionsInWindow'; maxTransitions: number; windowSec: number; message: string }
  | { type: 'MustNotAlertWhenTtcInvalidAndRelLow'; relMpsMax: number; message: string };

export type McawSegment = {
  tFromSec: number;
  tToSec: number;
  label: string;
  distanceM: McawProfile;
  approachSpeedMps: McawProfile;
  ttcSec: McawProfile;
  ttcSlopeSecPerSec?: McawProfile;
  cutInActive?: McawProfile;
  brakeCueActive?: McawProfile;
  brakeCueStrength?: McawProfile;
  roiContainment?: McawProfile;
  egoOffsetN?: McawProfile;
  qualityWeight?: McawProfile;
  leanDeg?: McawProfile;
};

export type McawScenarioSpec = {
  id: string;
  title: string;
  domain: Domain;
  vehicle: Vehicle;
  notes: string;
  config: McawConfig;
  expectations: McawExpectation[];
  segments: McawSegment[];
};

export type ValidationIssue = { kind: 'error' | 'warn'; message: string };

export type DraftMeta = Partial<Pick<McawScenarioSpec, 'title' | 'domain' | 'vehicle' | 'notes'>> & {
  leanDeg?: number | null;
  expectations?: McawExpectation[];
};

const ALLOWED_DOMAINS: Domain[] = ['CITY', 'TUNNEL', 'HIGHWAY', 'RURAL'];
const ALLOWED_VEHICLES: Vehicle[] = ['CAR', 'MOTO'];

function inferDomain(id: string): Domain {
  const upper = id.toUpperCase();
  if (upper.includes('CITY')) return 'CITY';
  if (upper.includes('TUNNEL')) return 'TUNNEL';
  if (upper.includes('HIGHWAY')) return 'HIGHWAY';
  return 'RURAL';
}

function profileToMcawNumeric(p: Profile): McawProfile {
  if (p.type === 'hold') return { kind: 'hold', value: p.value };
  if (p.type === 'linear') return { kind: 'linear', from: p.from, to: p.to };
  return { kind: 'linear', from: p.start, to: p.start + p.accelKmhPerSec };
}

export function sampleProfileFrames(profile: McawProfile, hz: number, durationSec: number): number[] {
  const frames = Math.max(1, Math.round(hz * durationSec));
  const values: number[] = [];
  for (let i = 0; i <= frames; i++) {
    const u = frames === 0 ? 0 : i / frames;
    if (profile.kind === 'hold') values.push(Number(profile.value));
    else if (profile.kind === 'linear') values.push(profile.from + (profile.to - profile.from) * u);
    else values.push(Number.NaN);
  }
  return values;
}

export function buildMcawSpec(draft: ScenarioDraft, meta: DraftMeta = {}): { spec: McawScenarioSpec; issues: ValidationIssue[] } {
  const riderSpeedKmh = draft.segments[0] ? evalProfile(draft.segments[0].speedKmh, 0, Math.max(0.001, draft.segments[0].tToSec - draft.segments[0].tFromSec)) : 0;
  const spec: McawScenarioSpec = {
    id: draft.scenarioId,
    title: meta.title ?? draft.scenarioId,
    domain: (meta.domain ?? inferDomain(draft.scenarioId)) as Domain,
    vehicle: (meta.vehicle ?? 'CAR') as Vehicle,
    notes: meta.notes ?? '[HUMAN_CONFIRMED] fill scenario notes',
    config: {
      effectiveMode: draft.effectiveMode,
      hz: draft.hz,
      riderSpeedMps: kmhToMps(riderSpeedKmh),
      qualityWeight: draft.defaultQw,
      roiContainment: draft.defaultRoi,
      egoOffsetN: 0,
      leanDeg: meta.leanDeg ?? null,
    },
    expectations: meta.expectations ?? [],
    segments: draft.segments.map((s) => ({
      tFromSec: s.tFromSec,
      tToSec: s.tToSec,
      label: s.label,
      distanceM: profileToMcawNumeric(s.distM),
      approachSpeedMps: profileToMcawNumeric(s.relMps),
      ttcSec: draft.ttcMode === 'derived' ? { kind: 'derived', eps: 0.1 } : profileToMcawNumeric(s.ttcSec),
      ttcSlopeSecPerSec: { kind: 'derived' },
      cutInActive: { kind: 'hold', value: s.cutIn },
      brakeCueActive: { kind: 'hold', value: s.brake },
      brakeCueStrength: { kind: 'hold', value: s.brake ? s.brakeStrength : 0 },
      roiContainment: profileToMcawNumeric(s.roi),
      egoOffsetN: profileToMcawNumeric(s.egoOffsetN),
      qualityWeight: profileToMcawNumeric(s.qW),
    })),
  };

  return { spec, issues: validateMcawSpec(spec, draft.relDrivenDistance) };
}

export function validateMcawSpec(spec: McawScenarioSpec, relDrivenDistance: boolean): ValidationIssue[] {
  const issues: ValidationIssue[] = [];

  if (!spec.id || !spec.title || !spec.notes || !spec.config) issues.push({ kind: 'error', message: 'Missing required scenario/config fields.' });
  if (!ALLOWED_DOMAINS.includes(spec.domain)) issues.push({ kind: 'error', message: `Invalid domain enum: ${spec.domain}` });
  if (!ALLOWED_VEHICLES.includes(spec.vehicle)) issues.push({ kind: 'error', message: `Invalid vehicle enum: ${spec.vehicle}` });
  if (!(spec.config.hz > 0)) issues.push({ kind: 'error', message: 'hz must be > 0.' });

  const segs = [...spec.segments].sort((a, b) => a.tFromSec - b.tFromSec);
  for (let i = 0; i < segs.length; i++) {
    const s = segs[i];
    if (!(s.tFromSec < s.tToSec)) issues.push({ kind: 'error', message: `Segment ${s.label} has invalid range (tFrom >= tTo).` });
    if (i > 0) {
      const prev = segs[i - 1];
      if (s.tFromSec < prev.tToSec) issues.push({ kind: 'error', message: `Segment ${s.label} overlaps ${prev.label}.` });
      if (s.tFromSec > prev.tToSec) issues.push({ kind: 'error', message: `Gap between ${prev.label} and ${s.label}.` });
    }

    if (s.ttcSec.kind === 'derived' && s.approachSpeedMps.kind !== 'derived') {
      const samples = sampleProfileFrames(s.approachSpeedMps, spec.config.hz, s.tToSec - s.tFromSec);
      if (samples.some((v) => Math.abs(v) < 0.15)) {
        issues.push({ kind: 'warn', message: `Segment ${s.label}: derived TTC with approach speed near 0 can be singular.` });
      }
    }

    if (relDrivenDistance && s.distanceM.kind !== 'hold') {
      issues.push({ kind: 'warn', message: `Segment ${s.label}: relDrivenDistance=true, detailed distance profile will be ignored.` });
    }
  }

  if (spec.expectations.length === 0) issues.push({ kind: 'warn', message: 'Expectations are missing.' });

  return issues;
}

function expectationToKotlin(e: McawExpectation): string {
  switch (e.type) {
    case 'MustEnterLevelBy':
      return `MustEnterLevelBy(level=${e.level}, latestSecAfterHazard=${e.latestSecAfterHazard}, hazardTimeSec=${e.hazardTimeSec}, message=${JSON.stringify(e.message)})`;
    case 'MustNotEnterLevel':
      return `MustNotEnterLevel(level=${e.level}, message=${JSON.stringify(e.message)})`;
    case 'MaxTransitionsInWindow':
      return `MaxTransitionsInWindow(maxTransitions=${e.maxTransitions}, windowSec=${e.windowSec}, message=${JSON.stringify(e.message)})`;
    case 'MustNotAlertWhenTtcInvalidAndRelLow':
      return `MustNotAlertWhenTtcInvalidAndRelLow(relMpsMax=${e.relMpsMax}, message=${JSON.stringify(e.message)})`;
  }
}

export function buildMcawMarkdown(spec: McawScenarioSpec, issues: ValidationIssue[]): string {
  const lines: string[] = [];
  lines.push(`# ${spec.id}`);
  lines.push('');
  lines.push(`- title: **${spec.title}**`);
  lines.push(`- domain/vehicle: **${spec.domain}/${spec.vehicle}**`);
  lines.push(`- notes: ${spec.notes}`);
  lines.push('');
  lines.push('## Config');
  lines.push(`- effectiveMode: ${spec.config.effectiveMode}`);
  lines.push(`- hz: ${spec.config.hz}`);
  lines.push(`- riderSpeedMps: ${round3(spec.config.riderSpeedMps)}`);
  lines.push('');
  lines.push('## Segments');
  lines.push('| tFrom | tTo | label | distanceM | approachSpeedMps | ttcSec |');
  lines.push('|---:|---:|---|---|---|---|');
  for (const s of spec.segments) {
    lines.push(`| ${s.tFromSec} | ${s.tToSec} | ${s.label} | ${JSON.stringify(s.distanceM)} | ${JSON.stringify(s.approachSpeedMps)} | ${JSON.stringify(s.ttcSec)} |`);
  }
  lines.push('');
  lines.push('## Expectations');
  if (spec.expectations.length === 0) lines.push('- (none)');
  for (const e of spec.expectations) lines.push(`- ${e.type}: ${JSON.stringify(e)}`);
  lines.push('');
  if (issues.length > 0) {
    lines.push('## Warnings/Errors');
    for (const i of issues) lines.push(`- **${i.kind.toUpperCase()}** ${i.message}`);
  }
  return lines.join('\n');
}

export function buildMcawKotlinSnippet(spec: McawScenarioSpec): string {
  const segments = spec.segments
    .map((s) => {
      const p = (prof: McawProfile): string => {
        if (prof.kind === 'hold') {
          const v = typeof prof.value === 'boolean' ? (prof.value ? 'true' : 'false') : `${Number(prof.value)}f`;
          return `{ _: Float -> ${v} }`;
        }
        if (prof.kind === 'linear') {
          return `{ t: Float -> ${prof.from}f + (${prof.to}f - ${prof.from}f) * ((t - ${s.tFromSec}f) / kotlin.math.max(1e-3f, (${s.tToSec}f - ${s.tFromSec}f))).coerceIn(0f, 1f) }`;
        }
        return `{ _: Float -> Float.NaN }`;
      };

      const optional = (name: string, prof?: McawProfile) => prof ? `, ${name}=${p(prof)}` : '';
      return `Segment(
      tFromSec=${s.tFromSec}f,
      tToSec=${s.tToSec}f,
      label=${JSON.stringify(s.label)},
      distanceM=${p(s.distanceM)},
      approachSpeedMps=${p(s.approachSpeedMps)},
      ttcSec=${p(s.ttcSec)}${optional('ttcSlopeSecPerSec', s.ttcSlopeSecPerSec)}${optional('cutInActive', s.cutInActive)}${optional('brakeCueActive', s.brakeCueActive)}${optional('brakeCueStrength', s.brakeCueStrength)}${optional('roiContainment', s.roiContainment)}${optional('egoOffsetN', s.egoOffsetN)}${optional('qualityWeight', s.qualityWeight)}${optional('leanDeg', s.leanDeg)}
    )`;
    })
    .join(',\n    ');

  const leanDegExpr = spec.config.leanDeg == null ? 'Float.NaN' : `${spec.config.leanDeg}f`;

  return `Scenario(
  id=${JSON.stringify(spec.id)},
  title=${JSON.stringify(spec.title)},
  domain=Domain.${spec.domain},
  vehicle=Vehicle.${spec.vehicle},
  notes=${JSON.stringify(spec.notes)},
  config=ScenarioConfig(
    effectiveMode=${spec.config.effectiveMode},
    hz=${spec.config.hz},
    riderSpeedMps=${spec.config.riderSpeedMps}f,
    qualityWeight=${spec.config.qualityWeight}f,
    roiContainment=${spec.config.roiContainment}f,
    egoOffsetN=${spec.config.egoOffsetN}f,
    leanDeg=${leanDegExpr}
  ),
  expectations=listOf(${spec.expectations.map(expectationToKotlin).join(', ')}),
  segments=listOf(
    ${segments}
  )
)`;
}


export function defaultDraftMeta(id: string): DraftMeta {
  return {
    title: id,
    domain: inferDomain(id),
    vehicle: 'CAR',
    notes: '[HUMAN_CONFIRMED] Doplňte popis scénáře.',
    leanDeg: null,
    expectations: [],
  };
}

export type Severity = 'info' | 'watch' | 'critical';

export type ServiceRow = {
  type: 'service';
  ts: number;
  message: string;
  file: string;
};

export type RiskRow = {
  type: 'risk';
  ts: number;
  risk?: number;
  level?: number;
  state?: string;
  reasonBits?: number;
  ttc?: number;
  dist?: number;
  relV?: number;
  roi?: number;
  quality?: number;
  cutIn?: number;
  brake?: number;
  egoBrake?: number;
  mode?: number;
  lockId?: number;
  finalTargetId?: number;
  label?: string;
  detScore?: number;
  reasonId?: number;
  riderSpeedMps?: number;
  riderSpeedConfidence?: number;
  riderSpeedSource?: number;
  riderSpeedAgeMs?: number;
  riderSpeedMethod?: number;
  rawColumns: string[];
  extraFields: Record<string, string>;
  source: string;
};

export type MetricsRow = {
  type: 'metrics';
  ts: number;
  lockId?: number;
  consecutiveDetections?: number;
  roiBottomPx?: number;
  boxBottomPx?: number;
  bottomTouch?: number;
  idSwitched?: number;
  relDerivValid?: number;
  relInvalidReasonMask?: number;
  distInputRaw?: number;
  distInput?: number;
  distM?: number;
  relSignedEma?: number;
  approachSpeed?: number;
  ttcFromDist?: number;
  ttc?: number;
  riskScore?: number;
  level?: number;
  reasonPayload?: number;
  reasonId?: number;
  trendState?: number;
  steadyMs?: number;
  approachMs?: number;
  steadySuppressActive?: number;
  reenterCooldownMs?: number;
  distSlopeEma?: number;
  relAbsEma?: number;
  distSource?: number;
  distConf?: number;
  riderSpeedRawMps?: number;
  riderSpeedMps?: number;
  riderSpeedConfidence?: number;
  riderSpeedSource?: number;
  riderSpeedAgeMs?: number;
  riderSpeedMethod?: number;
  rawColumns: string[];
  extraFields: Record<string, string>;
  source: string;
};

export type UnknownRow = {
  type: 'unknown';
  ts?: number;
  source: string;
  raw: string;
  columns: string[];
  reason: string;
};

export type ParsedLogData = {
  serviceRows: ServiceRow[];
  riskRows: RiskRow[];
  metricsRows: MetricsRow[];
  unknownRows: UnknownRow[];
  warnings: string[];
  schemaSummary: {
    hasService: boolean;
    hasRisk: boolean;
    hasMetrics: boolean;
    unknownCount: number;
    hasRiderSpeedContract: boolean;
  };
  dataQuality: {
    validRows: number;
    partialRows: number;
    rejectedRows: number;
    rejectedReasons: Record<string, number>;
  };
};

export type EventGroup = {
  id: string;
  tsStart: number;
  tsEnd: number;
  severity: Severity;
  summary: string;
  recommendation: string;
  riskRows: RiskRow[];
  metricsRows: MetricsRow[];
  serviceRows: ServiceRow[];
  sources: string[];
};

export type ScenarioTag = {
  id: string;
  tsStart: number;
  tsEnd: number;
  kind: 'near_stop_critical' | 'manual_note';
  note?: string;
};

export type SplitMode =
  | { type: 'auto'; label: string }
  | { type: 'manual-ts'; label: string; splitTs: number }
  | { type: 'manual-file'; label: string; filePrefix: string };

export type RelQualityResult = {
  totalRelevant: number;
  invalidCount: number;
  invalidRatio: number;
  byBit: Array<{ bit: number; count: number }>;
};

export type FalseRedResult = {
  before: { redBottomTouch: number; falseRed: number; rate: number };
  after: { redBottomTouch: number; falseRed: number; rate: number };
  formula: string;
};

export type SwitchBeneficialEvent = {
  ts: number;
  lockId?: number;
  finalTargetId?: number;
  beforeVar: number;
  afterVar: number;
  beneficial: boolean;
};

export type SwitchBeneficialResult = {
  total: number;
  beneficial: number;
  nonBeneficial: number;
  rate: number;
  events: SwitchBeneficialEvent[];
};

export type StandingSuppressorResult = {
  taggedNearStopCritical: number;
  missedCriticalNearStop: number;
  rate: number;
  events: Array<{ tagId: string; tsStart: number; tsEnd: number; note?: string }>;
};

export type SegmentPoint = {
  ts: number;
  lockId?: number;
  finalTargetId?: number;
  ttc?: number;
  rel?: number;
  dist?: number;
  risk?: number;
  level?: number;
  approachSpeed?: number;
  riderSpeed?: number;
  bottomTouch?: number;
  suppressStanding?: boolean;
  switched?: boolean;
  reasonBits?: number;
};

export type ObjectSegment = {
  id: string;
  kind: 'lock' | 'final-target';
  objectId: number;
  tsStart: number;
  tsEnd: number;
  points: SegmentPoint[];
};

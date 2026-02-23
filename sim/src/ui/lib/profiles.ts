import { clamp } from './utils';

export type Profile =
  | { type: 'hold'; value: number }
  | { type: 'linear'; from: number; to: number }
  | { type: 'accel'; start: number; accelKmhPerSec: number }; // speed only (km/h per s)

export type ProfileKind = Profile['type'];

export function evalProfile(p: Profile, tRelSec: number, segDurSec: number): number {
  if (p.type === 'hold') return p.value;
  if (p.type === 'linear') {
    const u = segDurSec <= 0 ? 0 : clamp(tRelSec / segDurSec, 0, 1);
    return p.from + (p.to - p.from) * u;
  }
  // accel
  return p.start + p.accelKmhPerSec * Math.max(0, tRelSec);
}

export function normalizeProfileKind(p: Profile, kind: ProfileKind): Profile {
  if (kind === 'hold') {
    const v = p.type === 'hold' ? p.value : p.type === 'linear' ? p.from : p.start;
    return { type: 'hold', value: v };
  }
  if (kind === 'linear') {
    const v = p.type === 'hold' ? p.value : p.type === 'linear' ? p.from : p.start;
    return { type: 'linear', from: v, to: v };
  }
  // accel
  const s = p.type === 'hold' ? p.value : p.type === 'linear' ? p.from : p.start;
  const a = p.type === 'accel' ? p.accelKmhPerSec : 0;
  return { type: 'accel', start: s, accelKmhPerSec: a };
}

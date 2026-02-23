import React from 'react';
import { Profile, ProfileKind, normalizeProfileKind } from '../lib/profiles';

export function ProfileEditor(props: {
  value: Profile;
  onChange: (p: Profile) => void;
  kinds: ProfileKind[];
  step?: number;
  width?: number;
  disabled?: boolean;
}) {
  const { value, onChange, kinds, step = 0.1, width = 90, disabled = false } = props;
  const kind = value.type as ProfileKind;

  return (
    <div style={{ display: 'flex', gap: 6, alignItems: 'center' }}>
      <select value={kind} onChange={(e) => onChange(normalizeProfileKind(value, e.target.value as ProfileKind))} disabled={disabled}>
        {kinds.map((k) => (
          <option key={k} value={k}>{k}</option>
        ))}
      </select>

      {kind === 'hold' && (
        <input style={{ width }} type="number" step={step} value={(value as any).value ?? 0} onChange={(e) => onChange({ type: 'hold', value: Number(e.target.value) })} disabled={disabled} />
      )}

      {kind === 'linear' && (
        <>
          <input style={{ width }} type="number" step={step} value={(value as any).from ?? 0} onChange={(e) => onChange({ type: 'linear', from: Number(e.target.value), to: (value as any).to ?? Number(e.target.value) })} title="from" disabled={disabled} />
          <span style={{ color: '#666' }}>→</span>
          <input style={{ width }} type="number" step={step} value={(value as any).to ?? 0} onChange={(e) => onChange({ type: 'linear', from: (value as any).from ?? Number(e.target.value), to: Number(e.target.value) })} title="to" disabled={disabled} />
        </>
      )}

      {kind === 'accel' && (
        <>
          <input style={{ width }} type="number" step={1} value={(value as any).start ?? 0} onChange={(e) => onChange({ type: 'accel', start: Number(e.target.value), accelKmhPerSec: (value as any).accelKmhPerSec ?? 0 })} title="start (km/h)" disabled={disabled} />
          <span style={{ color: '#666' }}>a</span>
          <input style={{ width }} type="number" step={0.1} value={(value as any).accelKmhPerSec ?? 0} onChange={(e) => onChange({ type: 'accel', start: (value as any).start ?? 0, accelKmhPerSec: Number(e.target.value) })} title="accel (km/h per s)" disabled={disabled} />
          <span style={{ color: '#666', fontSize: 12 }} title="Převod akcelerace. Záporná hodnota znamená brzdění.">
            ({(((value as any).accelKmhPerSec ?? 0) / 3.6).toFixed(2)} m/s²)
          </span>
        </>
      )}
    </div>
  );
}

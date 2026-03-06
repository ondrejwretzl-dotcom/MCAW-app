import React, { useState } from 'react';
import { ScenarioDraft, SegmentDraft } from '../lib/scenario';
import { Profile } from '../lib/profiles';
import { ProfileEditor } from './ProfileEditor';
import { clamp, downloadText, kmhToMps } from '../lib/utils';
import { templateC2, templateCutIn, templateEgoBrake, templateHighway } from '../lib/templates';
import { DraftMeta, McawExpectation } from '../lib/mcaw';

const H = (v: number): Profile => ({ type: 'hold', value: v });

function nearestDuration(totalSec: number): 10 | 30 | 60 {
  if (totalSec <= 10) return 10;
  if (totalSec <= 30) return 30;
  return 60;
}

const EXP_TYPES: McawExpectation['type'][] = ['MustEnterLevelBy', 'MustExitToLevelBy', 'MustNotEnterLevel', 'MaxTransitionsInWindow', 'MustNotAlertWhenTtcInvalidAndRelLow'];

export function BuilderPanel(props: {
  draft: ScenarioDraft;
  mcawMeta: DraftMeta;
  onChangeMeta: (meta: DraftMeta) => void;
  onChange: (draft: ScenarioDraft) => void;
  onRecompute: () => void;
  autoRecompute: boolean;
  onToggleAuto: (v: boolean) => void;
  onDownloadScenarioMd: () => void;
  onDownloadScenarioSpecJson: () => void;
  onDownloadScenarioKotlinSnippet: () => void;
}) {
  const { draft, mcawMeta, onChangeMeta, onChange, onRecompute, autoRecompute, onToggleAuto, onDownloadScenarioMd, onDownloadScenarioSpecJson, onDownloadScenarioKotlinSnippet } = props;
  const [assist, setAssist] = useState({ speedKmh: 55, distanceM: 35, reactionSec: 5, targetClearanceM: 1, decelMps2: 4 });
  const [assistMsg, setAssistMsg] = useState<string>('');

  const update = (patch: Partial<ScenarioDraft>) => {
    const next = { ...draft, ...patch };
    onChange(next);
    if (autoRecompute) onRecompute();
  };

  const updateExpectation = (idx: number, patch: Partial<McawExpectation>) => {
    const arr = [...(mcawMeta.expectations ?? [])];
    arr[idx] = { ...arr[idx], ...patch } as McawExpectation;
    onChangeMeta({ ...mcawMeta, expectations: arr });
  };

  const setExpectationType = (idx: number, type: McawExpectation['type']) => {
    const base: Record<McawExpectation['type'], McawExpectation> = {
      MustEnterLevelBy: { type, level: 1, latestSecAfterHazard: 1.5, hazardTimeSec: 0, message: '' },
      MustExitToLevelBy: { type, level: 0, latestSecAfterStart: 0.8, startTimeSec: 0, message: '' },
      MustNotEnterLevel: { type, level: 2, message: '' },
      MaxTransitionsInWindow: { type, maxTransitions: 2, windowSec: 3, message: '' },
      MustNotAlertWhenTtcInvalidAndRelLow: { type, relMpsMax: 0.2, message: '' },
    };
    const arr = [...(mcawMeta.expectations ?? [])];
    arr[idx] = base[type];
    onChangeMeta({ ...mcawMeta, expectations: arr });
  };

  const addExpectation = () => {
    const arr = [...(mcawMeta.expectations ?? []), { type: 'MustEnterLevelBy', level: 1, latestSecAfterHazard: 1.5, hazardTimeSec: 0, message: '' } as McawExpectation];
    onChangeMeta({ ...mcawMeta, expectations: arr });
  };

  const removeExpectation = (idx: number) => {
    const arr = [...(mcawMeta.expectations ?? [])];
    arr.splice(idx, 1);
    onChangeMeta({ ...mcawMeta, expectations: arr });
  };

  const updateSeg = (idx: number, patch: Partial<SegmentDraft>) => {
    const segs = draft.segments.map((s, i) => (i === idx ? { ...s, ...patch } : s));
    update({ segments: segs });
  };

  const addSeg = () => {
    const last = draft.segments[draft.segments.length - 1];
    const tFrom = last ? last.tToSec : 0;
    const tTo = Math.min(draft.durationSec, tFrom + 5);
    const seg: SegmentDraft = { tFromSec: tFrom, tToSec: tTo, label: `segment ${draft.segments.length + 1}`, speedKmh: H(50), relMps: H(1.0), distM: H(30), ttcSec: H(6), roi: H(1), qW: H(1), egoOffsetN: H(0), cutIn: false, brake: false, brakeStrength: 0 };
    update({ segments: [...draft.segments, seg] });
  };

  const removeLast = () => {
    if (draft.segments.length <= 1) return;
    update({ segments: draft.segments.slice(0, -1) });
  };

  const loadTemplate = (t: () => ScenarioDraft) => {
    const next = t();
    next.speedNoiseKmh = draft.speedNoiseKmh;
    next.speedNoiseSeed = draft.speedNoiseSeed;
    onChange(next);
    if (autoRecompute) onRecompute();
  };

  const applyRealWorldAssist = () => {
    const v0 = Math.max(0, assist.speedKmh);
    const v0mps = kmhToMps(v0);
    const reaction = Math.max(0, assist.reactionSec);
    const distance = Math.max(0.1, assist.distanceM);
    const clearance = Math.max(0, assist.targetClearanceM);
    const decel = Math.max(0.1, assist.decelMps2);

    const dReaction = v0mps * reaction;
    const remainingForBrake = distance - clearance - dReaction;
    if (remainingForBrake <= 0) {
      setAssistMsg('❌ Nerealizovatelné: ke kolizi by došlo během reakční doby (před začátkem brzdění).');
      return;
    }

    const needDecel = (v0mps * v0mps) / (2 * remainingForBrake);
    const usedDecel = Math.max(decel, needDecel);
    if (needDecel > decel) setAssistMsg(`⚠️ Zadané brzdění nestačí. Minimálně je potřeba ${needDecel.toFixed(2)} m/s².`);
    else setAssistMsg('✅ Scénář je fyzikálně realizovatelný pro zadanou reakci a brzdění.');

    const brakeTime = v0mps / usedDecel;
    const durationSec = nearestDuration(reaction + brakeTime + 1);
    const accelKmhPerSec = -usedDecel * 3.6;

    const next: ScenarioDraft = {
      ...draft,
      scenarioId: `${draft.scenarioId || 'scenario'}_assist`,
      durationSec,
      ttcMode: 'derived',
      relDrivenDistance: true,
      segments: [
        { tFromSec: 0, tToSec: reaction, label: 'reaction delay', speedKmh: H(v0), relMps: H(v0mps), distM: H(distance), ttcSec: H(10), roi: H(1), qW: H(1), egoOffsetN: H(0), cutIn: false, brake: false, brakeStrength: 0 },
        { tFromSec: reaction, tToSec: Math.min(durationSec, reaction + brakeTime), label: 'braking to stop', speedKmh: { type: 'accel', start: v0, accelKmhPerSec }, relMps: { type: 'linear', from: v0mps, to: 0 }, distM: H(distance), ttcSec: H(10), roi: H(1), qW: H(1), egoOffsetN: H(0), cutIn: false, brake: true, brakeStrength: clamp(usedDecel / 8, 0, 1) },
      ],
    };

    onChange(next);
    if (autoRecompute) onRecompute();
  };

  const downloadScenarioJson = () => downloadText(`${draft.scenarioId || 'scenario'}.json`, JSON.stringify(draft, null, 2), 'application/json');

  const loadScenarioJson = async (file: File) => {
    const txt = await file.text();
    onChange(JSON.parse(txt) as ScenarioDraft);
    if (autoRecompute) onRecompute();
  };

  return (
    <div style={{ padding: 12, border: '1px solid #eee', borderRadius: 10 }}>
      <details style={{ marginBottom: 12 }} open>
        <summary style={{ fontWeight: 700, cursor: 'pointer' }}>MCAW metadata & expectations</summary>
        <div style={{ marginTop: 8, display: 'grid', gap: 8 }}>
          <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
            <label title="Název scénáře čitelný pro člověka. Objeví se v markdownu i Kotlin snippetu.">Title<input value={mcawMeta.title ?? ''} onChange={(e) => onChangeMeta({ ...mcawMeta, title: e.target.value })} style={{ width: 260, marginLeft: 6 }} /></label>
            <label title="Doména prostředí scénáře: CITY/TUNNEL/HIGHWAY/RURAL. Ovlivňuje katalogizaci scénáře v MCAW.">Domain<select value={mcawMeta.domain ?? 'CITY'} onChange={(e) => onChangeMeta({ ...mcawMeta, domain: e.target.value as any })} style={{ marginLeft: 6 }}><option>CITY</option><option>TUNNEL</option><option>HIGHWAY</option><option>RURAL</option></select></label>
            <label title="Typ vozidla: CAR nebo MOTO. V MCAW je to povinné enum pole.">Vehicle<select value={mcawMeta.vehicle ?? 'CAR'} onChange={(e) => onChangeMeta({ ...mcawMeta, vehicle: e.target.value as any })} style={{ marginLeft: 6 }}><option>CAR</option><option>MOTO</option></select></label>
            <label title="Náklon vozidla ve stupních. Pro auto obvykle null, pro moto dle jízdní situace.">LeanDeg<input type="number" value={mcawMeta.leanDeg ?? ''} onChange={(e) => onChangeMeta({ ...mcawMeta, leanDeg: e.target.value === '' ? null : Number(e.target.value) })} style={{ width: 90, marginLeft: 6 }} /></label>
          </div>
          <label title="Poznámky k scénáři: kontext, zdroj dat, předpoklady. Doporučení: uvést hazard trigger a cíl testu.">Notes<textarea value={mcawMeta.notes ?? ''} onChange={(e) => onChangeMeta({ ...mcawMeta, notes: e.target.value })} rows={2} style={{ width: '100%', marginTop: 6 }} /></label>

          <div>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
              <b title="Expectations jsou QA pravidla, která MCAW kontroluje nad výstupním během.">Expectations (QA pravidla)</b>
              <button onClick={addExpectation}>+ Add expectation</button>
            </div>
            {(mcawMeta.expectations ?? []).map((exp, idx) => (
              <div key={idx} style={{ marginTop: 6, border: '1px solid #eee', borderRadius: 8, padding: 8, display: 'flex', gap: 8, flexWrap: 'wrap', alignItems: 'center' }}>
                <select value={exp.type} onChange={(e) => setExpectationType(idx, e.target.value as McawExpectation['type'])}>
                  {EXP_TYPES.map((t) => <option key={t} value={t}>{t}</option>)}
                </select>
                {'level' in exp && <label title="Úroveň alertu (1=orange, 2=red).">level<input type="number" value={exp.level} onChange={(e) => updateExpectation(idx, { level: Number(e.target.value) } as any)} style={{ width: 70, marginLeft: 6 }} /></label>}
                {'latestSecAfterHazard' in exp && <label title="Maximální čas od hazardTimeSec do vstupu do level. Doporučení: 1-2 s pro city.">latestSecAfterHazard<input type="number" step={0.1} value={exp.latestSecAfterHazard} onChange={(e) => updateExpectation(idx, { latestSecAfterHazard: Number(e.target.value) } as any)} style={{ width: 80, marginLeft: 6 }} /></label>}
                {'hazardTimeSec' in exp && <label title="Čas, kdy nastává hazard trigger (sekundy od startu scénáře).">hazardTimeSec<input type="number" step={0.1} value={exp.hazardTimeSec} onChange={(e) => updateExpectation(idx, { hazardTimeSec: Number(e.target.value) } as any)} style={{ width: 80, marginLeft: 6 }} /></label>}
                {'latestSecAfterStart' in exp && <label title="Maximální čas od startTimeSec do splnění 'exit' (návrat do level<=X).">latestSecAfterStart<input type="number" step={0.1} value={(exp as any).latestSecAfterStart} onChange={(e) => updateExpectation(idx, { latestSecAfterStart: Number(e.target.value) } as any)} style={{ width: 80, marginLeft: 6 }} /></label>}
                {'startTimeSec' in exp && <label title="Čas, od kterého začne platit pravidlo 'exit'.">startTimeSec<input type="number" step={0.1} value={(exp as any).startTimeSec} onChange={(e) => updateExpectation(idx, { startTimeSec: Number(e.target.value) } as any)} style={{ width: 80, marginLeft: 6 }} /></label>}
                {'maxTransitions' in exp && <label title="Maximální počet změn levelu v okně (anti-flapping).">maxTransitions<input type="number" value={exp.maxTransitions} onChange={(e) => updateExpectation(idx, { maxTransitions: Number(e.target.value) } as any)} style={{ width: 70, marginLeft: 6 }} /></label>}
                {'windowSec' in exp && <label title="Délka okna pro počítání přechodů levelů (s).">windowSec<input type="number" step={0.1} value={exp.windowSec} onChange={(e) => updateExpectation(idx, { windowSec: Number(e.target.value) } as any)} style={{ width: 70, marginLeft: 6 }} /></label>}
                {'relMpsMax' in exp && <label title="Maximální relativní rychlost pro pravidlo 'TT C invalid + low rel'. Doporučení 0.1-0.3 m/s.">relMpsMax<input type="number" step={0.1} value={exp.relMpsMax} onChange={(e) => updateExpectation(idx, { relMpsMax: Number(e.target.value) } as any)} style={{ width: 70, marginLeft: 6 }} /></label>}
                <label style={{ flex: 1, minWidth: 250 }} title="Text důvodu / očekávání v reportu. Udržujte krátké a akční.">message<input value={exp.message} onChange={(e) => updateExpectation(idx, { message: e.target.value } as any)} style={{ width: '100%', marginLeft: 6 }} /></label>
                <button onClick={() => removeExpectation(idx)}>remove</button>
              </div>
            ))}
          </div>
        </div>
      </details>

      <details style={{ marginBottom: 12 }}>
        <summary style={{ fontWeight: 700, cursor: 'pointer' }}>Real-world scenario assistant</summary>
        <div style={{ marginTop: 8, display: 'flex', gap: 10, flexWrap: 'wrap', alignItems: 'center' }}>
          <label title="Počáteční rychlost ego vozidla. Doporučení: město 30-60, dálnice 90-130 km/h.">v0 (km/h)<input type="number" value={assist.speedKmh} onChange={(e) => setAssist({ ...assist, speedKmh: Number(e.target.value) })} style={{ width: 80, marginLeft: 6 }} /></label>
          <span style={{ color: '#666' }}>{kmhToMps(assist.speedKmh).toFixed(2)} m/s</span>
          <label title="Počáteční vzdálenost k překážce (m).">distance (m)<input type="number" value={assist.distanceM} onChange={(e) => setAssist({ ...assist, distanceM: Number(e.target.value) })} style={{ width: 80, marginLeft: 6 }} /></label>
          <label title="Doba než řidič začne brzdit. Typicky 1-2 s dle situace.">reaction (s)<input type="number" value={assist.reactionSec} onChange={(e) => setAssist({ ...assist, reactionSec: Number(e.target.value) })} style={{ width: 70, marginLeft: 6 }} /></label>
          <label title="Požadovaný odstup při zastavení.">clearance (m)<input type="number" value={assist.targetClearanceM} onChange={(e) => setAssist({ ...assist, targetClearanceM: Number(e.target.value) })} style={{ width: 70, marginLeft: 6 }} /></label>
          <label title="Brzdění v m/s². Komfort ~3, silné ~6-8.">braking (m/s²)<input type="number" step={0.1} value={assist.decelMps2} onChange={(e) => setAssist({ ...assist, decelMps2: Number(e.target.value) })} style={{ width: 70, marginLeft: 6 }} /></label>
          <button onClick={applyRealWorldAssist}>Apply realistic scenario</button>
          {assistMsg && <span>{assistMsg}</span>}
        </div>
      </details>

      <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', marginBottom: 12 }}>
        <label style={{ display: 'flex', gap: 8, alignItems: 'center' }} title="Technické ID scénáře, používá se jako filename a Scenario.id.">Scenario ID<input value={draft.scenarioId} onChange={(e) => update({ scenarioId: e.target.value })} style={{ width: 220 }} /></label>
        <label style={{ display: 'flex', gap: 8, alignItems: 'center' }} title="Vzorkování simulace (frames/s). Vyšší hodnota = jemnější průběh.">Hz<input type="number" min={1} max={60} value={draft.hz} onChange={(e) => update({ hz: Number(e.target.value) })} style={{ width: 70 }} /></label>
        <label style={{ display: 'flex', gap: 8, alignItems: 'center' }} title="derived=počítá TTC z dist/rel, explicit=ruční TTC profil.">TTC mode<select value={draft.ttcMode} onChange={(e) => update({ ttcMode: e.target.value as any })}><option value="derived">derived(dist/rel)</option><option value="explicit">explicit(profile)</option></select></label>
        <label style={{ display: 'flex', gap: 8, alignItems: 'center' }} title="ON: distance se počítá integrací z rel; segmentové distance profily se ignorují (mimo inicializaci).">REL-driven distance<input type="checkbox" checked={draft.relDrivenDistance} onChange={(e) => update({ relDrivenDistance: e.target.checked })} /></label>
        <label style={{ display: 'flex', gap: 8, alignItems: 'center' }} title="Přidaný šum do rychlosti pro robustnost testu. Doporučení 0-2 km/h.">Speed noise (± km/h)<input type="number" min={0} max={10} step={0.5} value={draft.speedNoiseKmh} onChange={(e) => update({ speedNoiseKmh: Number(e.target.value) })} style={{ width: 70 }} /></label>
        <label style={{ display: 'flex', gap: 8, alignItems: 'center' }} title="Seed pseudo-RNG pro deterministický výsledek.">Seed<input type="number" value={draft.speedNoiseSeed} onChange={(e) => update({ speedNoiseSeed: Number(e.target.value) })} style={{ width: 90 }} /></label>
        <button onClick={onRecompute}>Recompute</button>
        <label style={{ display: 'flex', gap: 8, alignItems: 'center' }}>Auto<input type="checkbox" checked={autoRecompute} onChange={(e) => onToggleAuto(e.target.checked)} /></label>
        <button onClick={downloadScenarioJson}>Download scenario JSON</button>
        <label style={{ display: 'flex', gap: 8, alignItems: 'center' }}>Load JSON<input type="file" accept=".json" onChange={(e) => { const f = e.target.files?.[0]; if (f) void loadScenarioJson(f); }} /></label>
        <button onClick={onDownloadScenarioMd}>Download scenario MD</button>
        <button onClick={onDownloadScenarioSpecJson}>Download scenario_spec.json</button>
        <button onClick={onDownloadScenarioKotlinSnippet}>Download scenario_kotlin.snippet.kt</button>
      </div>

      <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', marginBottom: 12 }}>
        <button onClick={() => loadTemplate(templateC2)}>Template: C2 closing</button>
        <button onClick={() => loadTemplate(templateHighway)}>Template: highway steady</button>
        <button onClick={() => loadTemplate(templateCutIn)}>Template: cut-in</button>
        <button onClick={() => loadTemplate(templateEgoBrake)}>Template: ego braking</button>
      </div>

      <div style={{ fontWeight: 700, marginBottom: 6 }}>Segments</div>
      <div style={{ marginBottom: 8, color: '#555', fontSize: 13 }}>
        Vstupní závislosti: <b>TTC mode=derived</b> ⇒ TTC je dopočítané z dist/rel. <b>REL-driven distance=ON</b> ⇒ distance je integrovaná z rel.
      </div>

      <div style={{ overflowX: 'auto', maxHeight: 380, border: '1px solid #eee', borderRadius: 10 }}>
        <table style={{ borderCollapse: 'collapse', minWidth: 1100 }}>
          <thead><tr>{['tFrom', 'tTo', 'label', 'speed (km/h)', 'rel (m/s)', 'dist (m)', 'TTC (s)', 'ROI', 'qW', 'egoOffset', 'cutIn', 'brake', 'strength'].map((h) => (<th key={h} style={{ textAlign: 'left', borderBottom: '1px solid #ddd', padding: '6px 8px', whiteSpace: 'nowrap', position: 'sticky', top: 0, background: '#fff', zIndex: 1 }}>{h}</th>))}</tr></thead>
          <tbody>
            {draft.segments.map((s, idx) => (
              <tr key={idx}>
                <td style={{ padding: '6px 8px' }}><input type="number" value={s.tFromSec} onChange={(e) => updateSeg(idx, { tFromSec: Number(e.target.value) })} style={{ width: 70 }} /></td>
                <td style={{ padding: '6px 8px' }}><input type="number" value={s.tToSec} onChange={(e) => updateSeg(idx, { tToSec: Number(e.target.value) })} style={{ width: 70 }} /></td>
                <td style={{ padding: '6px 8px' }}><input value={s.label} onChange={(e) => updateSeg(idx, { label: e.target.value })} style={{ width: 160 }} /></td>
                <td style={{ padding: '6px 8px' }}><ProfileEditor value={s.speedKmh} onChange={(p) => updateSeg(idx, { speedKmh: p })} kinds={['hold', 'linear', 'accel']} step={1} /></td>
                <td style={{ padding: '6px 8px' }}><ProfileEditor value={s.relMps} onChange={(p) => updateSeg(idx, { relMps: p })} kinds={['hold', 'linear']} step={0.1} /></td>
                <td style={{ padding: '6px 8px' }}><ProfileEditor value={s.distM} onChange={(p) => updateSeg(idx, { distM: p })} kinds={['hold', 'linear']} step={0.5} disabled={draft.relDrivenDistance} /></td>
                <td style={{ padding: '6px 8px' }}><ProfileEditor value={s.ttcSec} onChange={(p) => updateSeg(idx, { ttcSec: p })} kinds={['hold', 'linear']} step={0.1} disabled={draft.ttcMode === 'derived'} /></td>
                <td style={{ padding: '6px 8px' }}><ProfileEditor value={s.roi} onChange={(p) => updateSeg(idx, { roi: p })} kinds={['hold', 'linear']} step={0.05} /></td>
                <td style={{ padding: '6px 8px' }}><ProfileEditor value={s.qW} onChange={(p) => updateSeg(idx, { qW: p })} kinds={['hold', 'linear']} step={0.05} /></td>
                <td style={{ padding: '6px 8px' }}><ProfileEditor value={s.egoOffsetN} onChange={(p) => updateSeg(idx, { egoOffsetN: p })} kinds={['hold', 'linear']} step={0.05} /></td>
                <td style={{ padding: '6px 8px' }}><input type="checkbox" checked={s.cutIn} onChange={(e) => updateSeg(idx, { cutIn: e.target.checked })} /></td>
                <td style={{ padding: '6px 8px' }}><input type="checkbox" checked={s.brake} onChange={(e) => updateSeg(idx, { brake: e.target.checked })} /></td>
                <td style={{ padding: '6px 8px' }}><input type="number" min={0} max={1} step={0.1} value={s.brakeStrength} onChange={(e) => updateSeg(idx, { brakeStrength: Number(e.target.value) })} style={{ width: 70 }} /></td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <div style={{ marginTop: 10, display: 'flex', gap: 8 }}>
        <button onClick={addSeg}>+ Add segment</button>
        <button onClick={removeLast} disabled={draft.segments.length <= 1}>− Remove last</button>
      </div>
    </div>
  );
}

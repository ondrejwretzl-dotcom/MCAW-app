import React from 'react';
import { Plot } from '../../charts/Plot';
import { Thresholds } from '../lib/types';

export function PlotsPanel(props: {
  t: number[];
  baseEma: number[];
  tunedEma?: number[];
  baseRaw?: number[];
  tunedRaw?: number[];
  baseLevel?: number[];
  tunedLevel?: number[];
  thresholds: Thresholds;
  baseFirstOrange?: number | null;
  baseFirstRed?: number | null;
  tunedFirstOrange?: number | null;
  tunedFirstRed?: number | null;

  showInputPlot: boolean;
  inputSeries: { name: string; x: number[]; y: number[]; enabled: boolean }[];
  onToggleInputPlot: (v: boolean) => void;
  onToggleInputSeries: (name: string, enabled: boolean) => void;
}) {
  const {
    t,
    baseEma,
    tunedEma,
    baseRaw,
    tunedRaw,
    baseLevel,
    tunedLevel,
    thresholds,
    baseFirstOrange,
    baseFirstRed,
    tunedFirstOrange,
    tunedFirstRed,
    showInputPlot,
    inputSeries,
    onToggleInputPlot,
    onToggleInputSeries,
  } = props;

  const sanitize = (x: number[], y: Array<number | undefined | null>) => {
    const n = Math.min(x.length, y.length);
    const xs = x.slice(0, n);
    const ys = y.slice(0, n).map((v) => (v != null && Number.isFinite(v) ? (v as number) : null));
    return { xs, ys };
  };

  const riskData: any[] = [];
  {
    const { xs, ys } = sanitize(t, baseEma);
    if (xs.length >= 2) riskData.push({ type: 'scatter', x: xs, y: ys, name: 'emaRisk (baseline)', mode: 'lines', connectgaps: true, showlegend: true });
  }
  if (baseRaw) {
    const { xs, ys } = sanitize(t, baseRaw);
    if (xs.length >= 2) riskData.push({ type: 'scatter', x: xs, y: ys, name: 'rawRisk (baseline)', mode: 'lines', connectgaps: true, showlegend: true });
  }
  if (tunedEma) {
    const { xs, ys } = sanitize(t, tunedEma);
    if (xs.length >= 2) riskData.push({ type: 'scatter', x: xs, y: ys, name: 'emaRisk (what-if)', mode: 'lines', line: { dash: 'dot' }, connectgaps: true, showlegend: true });
  }
  if (tunedRaw) {
    const { xs, ys } = sanitize(t, tunedRaw);
    if (xs.length >= 2) riskData.push({ type: 'scatter', x: xs, y: ys, name: 'rawRisk (what-if)', mode: 'lines', line: { dash: 'dot' }, connectgaps: true, showlegend: true });
  }

  if (baseLevel) {
    const { xs, ys } = sanitize(t, baseLevel);
    if (xs.length >= 2) riskData.push({ type: 'scatter', x: xs, y: ys, name: 'level (baseline)', mode: 'lines', yaxis: 'y2', connectgaps: true, showlegend: true });
  }
  if (tunedLevel) {
    const { xs, ys } = sanitize(t, tunedLevel);
    if (xs.length >= 2) riskData.push({ type: 'scatter', x: xs, y: ys, name: 'level (what-if)', mode: 'lines', yaxis: 'y2', line: { dash: 'dot' }, connectgaps: true, showlegend: true });
  }

  const distanceSeries = inputSeries.find((s) => s.name === 'distance (m)');
  const collisions = distanceSeries
    ? distanceSeries.x
        .slice(0, Math.min(distanceSeries.x.length, distanceSeries.y.length))
        .filter((_, i) => Number(distanceSeries.y[i]) <= 0.0001)
    : [];

  const addV = (arr: any[], x: number, dash: 'dash' | 'dot', color?: string) => {
    arr.push({ type: 'line', x0: x, x1: x, y0: 0, y1: 1, yref: 'paper', line: { dash, width: 1, color } });
  };

  const riskShapes: any[] = [
    { type: 'line', x0: t[0] ?? 0, x1: t[t.length - 1] ?? 1, y0: thresholds.orangeOn, y1: thresholds.orangeOn, line: { dash: 'dot', width: 1, color: '#f59e0b' } },
    { type: 'line', x0: t[0] ?? 0, x1: t[t.length - 1] ?? 1, y0: thresholds.redOn, y1: thresholds.redOn, line: { dash: 'dot', width: 1, color: '#dc2626' } },
  ];

  if (baseFirstOrange != null) addV(riskShapes, baseFirstOrange, 'dash', '#f59e0b');
  if (baseFirstRed != null) addV(riskShapes, baseFirstRed, 'dash', '#dc2626');
  if (tunedFirstOrange != null) addV(riskShapes, tunedFirstOrange, 'dot', '#f59e0b');
  if (tunedFirstRed != null) addV(riskShapes, tunedFirstRed, 'dot', '#dc2626');
  collisions.slice(0, 50).forEach((x) => addV(riskShapes, x, 'dot', '#ef4444'));

  const riskLayout: any = {
    height: 420,
    margin: { l: 50, r: 50, t: 30, b: 40 },
    yaxis: { title: 'risk', range: [0, 1] },
    yaxis2: { title: 'level', overlaying: 'y', side: 'right', range: [0, 2], dtick: 1 },
    xaxis: { title: 't (s)', range: [t[0] ?? 0, t[t.length - 1] ?? 1] },
    shapes: riskShapes,
    showlegend: true,
    legend: { orientation: 'h' },
  };

  const enabledInputs = inputSeries.filter((s) => s.enabled);
  const inputData: any[] = enabledInputs
    .map((s) => {
      const { xs, ys } = sanitize(s.x, s.y);
      return xs.length >= 2 ? { type: 'scatter', x: xs, y: ys, name: s.name, mode: 'lines', connectgaps: true, showlegend: true } : null;
    })
    .filter(Boolean);

  const inputShapes: any[] = [
    { type: 'line', x0: t[0] ?? 0, x1: t[t.length - 1] ?? 1, y0: thresholds.orangeOn, y1: thresholds.orangeOn, yref: 'y2', line: { dash: 'dot', width: 1, color: '#f59e0b' } },
    { type: 'line', x0: t[0] ?? 0, x1: t[t.length - 1] ?? 1, y0: thresholds.redOn, y1: thresholds.redOn, yref: 'y2', line: { dash: 'dot', width: 1, color: '#dc2626' } },
  ];

  if (baseFirstOrange != null) addV(inputShapes, baseFirstOrange, 'dash', '#f59e0b');
  if (baseFirstRed != null) addV(inputShapes, baseFirstRed, 'dash', '#dc2626');
  if (tunedFirstOrange != null) addV(inputShapes, tunedFirstOrange, 'dot', '#f59e0b');
  if (tunedFirstRed != null) addV(inputShapes, tunedFirstRed, 'dot', '#dc2626');
  collisions.slice(0, 50).forEach((x) => addV(inputShapes, x, 'dot', '#ef4444'));

  const inputLayout: any = {
    height: 320,
    margin: { l: 50, r: 70, t: 30, b: 40 },
    xaxis: { title: 't (s)' },
    yaxis2: {
      title: 'alert thresholds',
      overlaying: 'y',
      side: 'right',
      range: [0, 1],
      showgrid: false,
    },
    shapes: inputShapes,
    showlegend: true,
    legend: { orientation: 'h' },
  };

  const plotKey = `${t.length}-${riskData.length}-${(baseEma && baseEma.length) || 0}-${(tunedEma && tunedEma.length) || 0}`;

  return (
    <div style={{ marginTop: 12 }}>
      <Plot key={`risk-${plotKey}`} traces={riskData} layout={riskLayout} />

      <div style={{ marginTop: 10, display: 'flex', gap: 12, alignItems: 'center', flexWrap: 'wrap' }}>
        <label style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
          Show input plot
          <input type="checkbox" checked={showInputPlot} onChange={(e) => onToggleInputPlot(e.target.checked)} />
        </label>

        {showInputPlot &&
          inputSeries.map((s) => (
            <label key={s.name} style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
              {s.name}
              <input type="checkbox" checked={s.enabled} onChange={(e) => onToggleInputSeries(s.name, e.target.checked)} />
            </label>
          ))}
      </div>

      {showInputPlot && <div style={{ marginTop: 8 }}><Plot key={`input-${plotKey}`} traces={inputData} layout={inputLayout} /></div>}
    </div>
  );
}

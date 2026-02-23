import React, { useEffect, useRef } from 'react';
import Plotly from 'plotly.js-dist-min';

export type Trace = Partial<Plotly.PlotData>;

export function Plot({ traces, layout, style }: { traces: Trace[]; layout: Partial<Plotly.Layout>; style?: React.CSSProperties }) {
  const ref = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    if (!ref.current) return;
    Plotly.react(ref.current, traces as Plotly.Data[], layout as Plotly.Layout, {
      displaylogo: false,
      responsive: true,
    });
    return () => {
      if (ref.current) Plotly.purge(ref.current);
    };
  }, [traces, layout]);

  return <div ref={ref} style={{ width: '100%', height: 420, ...style }} />;
}

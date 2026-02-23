import React from 'react';
import { ScenarioDoc } from '../lib/types';

export function UploadPanel(props: {
  scenarios: ScenarioDoc[];
  selectedScenarioId: string | null;
  onSelectScenario: (id: string) => void;
  onFilesChosen: (files: FileList) => void;
}) {
  const { scenarios, selectedScenarioId, onSelectScenario, onFilesChosen } = props;

  return (
    <div style={{ padding: 12, border: '1px solid #eee', borderRadius: 10 }}>
      <div style={{ display: 'flex', gap: 12, alignItems: 'center', flexWrap: 'wrap', marginBottom: 12 }}>
        <label style={{ fontWeight: 600 }}>Upload</label>
        <input
          type="file"
          multiple
          accept=".jsonl,.md"
          onChange={(e) => {
            if (e.target.files) onFilesChosen(e.target.files);
          }}
        />
        {scenarios.length > 0 && (
          <label style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
            Scenario
            <select
              value={selectedScenarioId ?? scenarios[0]?.scenarioId ?? ''}
              onChange={(e) => onSelectScenario(e.target.value)}
            >
              {scenarios.map((s) => (
                <option key={s.scenarioId} value={s.scenarioId}>
                  {s.scenarioId}
                </option>
              ))}
            </select>
          </label>
        )}
      </div>

      <div style={{ color: '#666' }}>
        Tip: upload <code>*.frames.jsonl</code> (per-frame trace) and optionally matching <code>*.md</code> for notes.
      </div>
    </div>
  );
}

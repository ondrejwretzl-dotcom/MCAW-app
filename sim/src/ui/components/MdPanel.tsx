import React from 'react';

export function MdPanel({ title, md }: { title: string; md?: string }) {
  if (!md) return null;
  return (
    <details style={{ marginTop: 10 }}>
      <summary style={{ cursor: 'pointer', fontWeight: 600 }}>{title}</summary>
      <pre style={{ whiteSpace: 'pre-wrap', marginTop: 8, padding: 10, background: '#fafafa', border: '1px solid #eee' }}>
        {md}
      </pre>
    </details>
  );
}

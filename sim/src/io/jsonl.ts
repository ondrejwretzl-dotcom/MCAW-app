export function parseJsonl(text: string): any[] {
  const lines = text.split(/\r?\n/).filter(l => l.trim().length > 0);
  const out: any[] = [];
  for (let i = 0; i < lines.length; i++) {
    try {
      out.push(JSON.parse(lines[i]));
    } catch (e) {
      throw new Error(`Invalid JSONL at line ${i + 1}: ${(e as Error).message}`);
    }
  }
  return out;
}

export function toJsonl(objs: any[]): string {
  return objs.map(o => JSON.stringify(o)).join('\n') + '\n';
}

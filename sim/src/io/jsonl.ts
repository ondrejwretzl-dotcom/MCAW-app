export function parseJsonl(text: string): any[] {
  const lines = text.split(/\r?\n/).filter(l => l.trim().length > 0);
  const out: any[] = [];
  for (let i = 0; i < lines.length; i++) {
    try {
      // Some legacy/hand-authored JSONL may contain non-JSON numeric literals (NaN/Infinity).
      // Be tolerant: sanitize them to `null` before parsing.
      const sanitized = replaceNonFiniteJsonTokens(lines[i]);
      out.push(JSON.parse(sanitized));
    } catch (e) {
      throw new Error(`Invalid JSONL at line ${i + 1}: ${(e as Error).message}`);
    }
  }
  return out;
}

export function toJsonl(objs: any[]): string {
  return objs.map(o => JSON.stringify(o)).join('\n') + '\n';
}

/**
 * Replace invalid JSON number tokens NaN/Infinity/-Infinity that may appear in JSONL.
 * Only replaces occurrences outside of JSON strings.
 */
function replaceNonFiniteJsonTokens(line: string): string {
  let out = '';
  let i = 0;
  let inStr = false;
  let escape = false;

  const isIdChar = (c: string) => /[A-Za-z0-9_]/.test(c);

  while (i < line.length) {
    const ch = line[i];
    if (inStr) {
      out += ch;
      if (escape) {
        escape = false;
      } else if (ch === '\\') {
        escape = true;
      } else if (ch === '"') {
        inStr = false;
      }
      i++;
      continue;
    }

    if (ch === '"') {
      inStr = true;
      out += ch;
      i++;
      continue;
    }

    // Outside strings: check for NaN / Infinity / -Infinity tokens with word boundaries.
    const rest = line.slice(i);
    const prev = i > 0 ? line[i - 1] : '';
    const prevOk = prev === '' || !isIdChar(prev);

    if (prevOk && rest.startsWith('NaN') && !isIdChar(rest[3] ?? '')) {
      out += 'null';
      i += 3;
      continue;
    }
    if (prevOk && rest.startsWith('Infinity') && !isIdChar(rest[8] ?? '')) {
      out += 'null';
      i += 8;
      continue;
    }
    if (prevOk && rest.startsWith('-Infinity') && !isIdChar(rest[9] ?? '')) {
      out += 'null';
      i += 9;
      continue;
    }

    out += ch;
    i++;
  }

  return out;
}

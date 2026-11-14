// Offline check of every chapter's code excerpts: the file resolves, from/to match, and each emphasis
// matches at least one line of the excerpt. Run before a long render:  node scripts/check-sources.mjs [NN …]
import { readFileSync, existsSync, readdirSync } from 'node:fs';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
const here = resolve(dirname(fileURLToPath(import.meta.url)), '..'), repo = resolve(here, '../..');
const chapters = process.argv.slice(2).length ? process.argv.slice(2) : readdirSync(resolve(here, 'chapters')).sort();
let bad = 0;
for (const ch of chapters) {
  const script = JSON.parse(readFileSync(resolve(here, `chapters/${ch}/script.json`), 'utf8'));
  for (const sc of script.scenes) {
    if (!sc.code) continue;
    try {
      const base = sc.code.file.replace(/\.ec\.yaml$|\.yaml$|\.ec$/, '');
      const opts = base === sc.code.file ? [sc.code.file] : [`${base}.ec.yaml`, `${base}.yaml`, `${base}.ec`];
      const file = opts.find((f) => existsSync(resolve(repo, f)));
      if (!file) throw new Error(`no file ${sc.code.file}`);
      const all = readFileSync(resolve(repo, file), 'utf8').replace(/\n$/, '').split('\n');
      const find = (v, start) => { if (typeof v === 'number') return v - 1; const i = all.findIndex((l, j) => j >= start && new RegExp(v).test(l)); if (i < 0) throw new Error(`no line /${v}/`); return i; };
      const a = sc.code.from == null ? 0 : find(sc.code.from, 0), b = sc.code.to == null ? all.length - 1 : find(sc.code.to, a + 1);
      const lines = all.slice(a, b + 1);
      for (const e of sc.spec?.emphasis || []) {
        const hit = lines.some((l, i) => e.match.some((m) => new RegExp(m).test(l)) || (e.lines || []).some(([x, y]) => a + 1 + i >= x && a + 1 + i <= y));
        if (!hit) throw new Error(`emphasis at ${e.at} matches nothing: ${e.match.join(' | ')}`);
        const missing = e.match.filter((m) => !lines.some((l) => new RegExp(m).test(l)));
        if (missing.length) console.log(`  note ch${ch}/${sc.id}: part of emphasis ${e.at} unmatched: ${missing.join(' | ')}`);
      }
      if (typeof sc.code.from === 'number' || typeof sc.code.to === 'number') console.log(`  note ch${ch}/${sc.id}: line-number range ${sc.code.from}-${sc.code.to} (${lines[0]?.trim().slice(0, 50)} … ${lines.at(-1)?.trim().slice(0, 50)})`);
    } catch (e) { bad++; console.log(`FAIL ch${ch}/${sc.id}: ${e.message}`); }
  }
}
console.log(bad ? `${bad} problem(s)` : 'all excerpts OK');
process.exit(bad ? 1 : 0);

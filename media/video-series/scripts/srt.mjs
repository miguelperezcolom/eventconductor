// Subtitles for one master, in both languages, timed to THAT master's scenes.
//   node scripts/srt.mjs <NN> <master lang>   → build/chNN/<lang>/chNN-<lang>.en.srt and .es.srt
// The master's own language: one cue per narrated sentence, at the time it is spoken.
// The other language: each scene's sentences of the other language's narration, spread over the time
// this master speaks in that scene (in proportion to their own spoken length), so both tracks change
// scene together. Cues are split to at most 2 lines of 42 characters.
import { readFileSync, writeFileSync } from 'node:fs';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const [chapter, lang] = process.argv.slice(2);
const json = (p) => JSON.parse(readFileSync(p, 'utf8'));
const tl = json(resolve(here, `build/ch${chapter}/${lang}/timeline.json`));
const MAXL = 42;

const ts = (s) => { const ms = Math.round(s * 1000); const h = Math.floor(ms / 3600000), m = Math.floor(ms / 60000) % 60, sec = Math.floor(ms / 1000) % 60; return `${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}:${String(sec).padStart(2, '0')},${String(ms % 1000).padStart(3, '0')}`; };
/** Words → lines of ≤ MAXL; lines → cues of ≤ 2 lines, timed in proportion to their characters. */
function split(text, a, b) {
  const lines = []; let cur = '';
  for (const w of text.split(/\s+/)) { if (cur && (cur + ' ' + w).length > MAXL) { lines.push(cur); cur = w; } else cur = cur ? cur + ' ' + w : w; }
  if (cur) lines.push(cur);
  const groups = [];
  for (let i = 0; i < lines.length; i += 2) groups.push(lines.slice(i, i + 2));
  if (groups.length > 1 && groups[groups.length - 1].length === 1 && groups[groups.length - 1][0].length < 14) { const tail = groups.pop()[0]; const prev = groups[groups.length - 1]; prev[prev.length - 1] += ' ' + tail; }
  const total = groups.reduce((s, g) => s + g.join(' ').length, 0);
  let t = a; return groups.map((g) => { const d = ((b - a) * g.join(' ').length) / total; const c = { a: t, b: t + d, text: g.join('\n') }; t += d; return c; });
}
function write(file, cues) {
  writeFileSync(file, cues.map((c, i) => `${i + 1}\n${ts(c.a)} --> ${ts(c.b)}\n${c.text}\n`).join('\n'));
  console.log(`srt ${file}: ${cues.length} cues`);
}
for (const sub of ['en', 'es']) {
  const cues = [];
  if (sub === lang) {
    for (const sc of tl.scenes) for (const c of sc.cues) cues.push(...split(c.text, sc.start + c.start, sc.start + c.end));
  } else {
    const other = json(resolve(here, `build/ch${chapter}/${sub}/audio/timing.json`));
    const byId = Object.fromEntries(other.scenes.map((s) => [s.id, s.sentences]));
    for (const sc of tl.scenes) {
      const sents = byId[sc.id] || [];
      if (!sents.length || !sc.cues.length) continue;
      const a = sc.start + sc.cues[0].start, b = sc.start + sc.cues[sc.cues.length - 1].end;
      const tot = sents.reduce((s, x) => s + x.duration, 0);
      let t = a;
      for (const s of sents) { const d = ((b - a) * s.duration) / tot; cues.push(...split(s.text, t, t + d - 0.05)); t += d; }
    }
  }
  write(resolve(here, `build/ch${chapter}/${lang}/ch${chapter}-${lang}.${sub}.srt`), cues);
}

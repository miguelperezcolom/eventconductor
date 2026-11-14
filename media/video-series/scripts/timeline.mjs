// The picture timeline of one master, from its own narration (timing.json of that language).
// Scene length = lead + its sentences (with a gap between them) + tail, never under scene.minDuration.
// Each language has its own timeline, so EN and ES masters may differ in length.
//
//   node scripts/timeline.mjs <script.json> <timing.json> <out timeline.json>
import { readFileSync, writeFileSync } from 'node:fs';

export const LEAD = 0.45, GAP = 0.3, TAIL = 0.7;

export function timeline(script, timing) {
  const byId = Object.fromEntries(timing.scenes.map((s) => [s.id, s]));
  let t = 0;
  const scenes = script.scenes.map((sc) => {
    const sents = byId[sc.id]?.sentences ?? [];
    let c = LEAD;
    const cues = sents.map((s) => { const cue = { start: +c.toFixed(3), end: +(c + s.duration).toFixed(3), text: s.text, file: s.file }; c += s.duration + GAP; return cue; });
    const speech = cues.length ? cues[cues.length - 1].end : 0;
    const extra = sc.extra ?? 0;                     // seconds of picture after the voice (e.g. a result to read)
    const dur = +Math.max(sc.minDuration ?? 0, speech + TAIL + extra).toFixed(3);
    const out = { id: sc.id, start: +t.toFixed(3), dur, cues };
    t += dur;
    return out;
  });
  return { lang: timing.lang, voice: timing.voice, engine: timing.engine, duration: +t.toFixed(3), scenes };
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const [scriptPath, timingPath, out] = process.argv.slice(2);
  const tl = timeline(JSON.parse(readFileSync(scriptPath, 'utf8')), JSON.parse(readFileSync(timingPath, 'utf8')));
  writeFileSync(out, JSON.stringify(tl, null, 1));
  console.log(`timeline ${tl.lang}: ${tl.duration.toFixed(1)}s, ${tl.scenes.length} scenes`);
}

// The YouTube texts of a chapter, for the owner to review before anything is uploaded (nothing is
// uploaded by this pipeline). Chapter timestamps come from each master's own timeline.
//   node scripts/youtube.mjs <NN> <out dir>   → <out>/chNN-youtube.md
import { readFileSync, writeFileSync, existsSync } from 'node:fs';
import { execSync } from 'node:child_process';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const [chapter, out] = process.argv.slice(2);
const json = (p) => JSON.parse(readFileSync(p, 'utf8'));
const script = json(resolve(here, `chapters/${chapter}/script.json`));
const sha = execSync('git rev-parse --short HEAD', { cwd: here }).toString().trim();
const lastTag = execSync('git describe --tags --abbrev=0 --match "v*"', { cwd: here }).toString().trim();
const mmss = (s) => `${Math.floor(s / 60)}:${String(Math.floor(s % 60)).padStart(2, '0')}`;
const DOCS = 'https://eventconductor.mateu.io/';
const docsUrl = DOCS + script.docs;
const codeUrl = `https://github.com/miguelperezcolom/eventconductor/tree/${sha}/${script.code}`;
const N = script.chapter;

const L = {
  en: {
    master: 'English master', chapters: 'Chapters', docs: 'Docs', code: 'Code',
    rec: (v) => `Recorded on EventConductor (main at ${sha}, after ${v}). Every screen is the real product running; ${script.standIns.en}. Voice: synthetic (Kokoro, local). Open source, MIT.`,
    playlist: 'Playlist', vis: 'Visibility', subs: 'Subtitles', audio: 'Audio language', lang: 'English',
  },
  es: {
    master: 'Máster en español', chapters: 'Capítulos', docs: 'Documentación', code: 'Código',
    rec: (v) => `Grabado sobre EventConductor (main en ${sha}, posterior a ${v}). Cada pantalla es el producto real funcionando; ${script.standIns.es}. Voz: sintética (Kokoro, local). Código abierto, licencia MIT.`,
    playlist: 'Lista', vis: 'Visibilidad', subs: 'Subtítulos', audio: 'Idioma del audio', lang: 'Español',
  },
};

function description(textLang, tl) {
  const byId = Object.fromEntries(tl.scenes.map((s) => [s.id, s]));
  const marks = script.youtubeChapters.map((c) => ({ t: byId[c.at].start, label: c[textLang] }));
  marks[0].t = 0;
  for (let i = 1; i < marks.length; i++) if (marks[i].t - marks[i - 1].t < 10) console.warn(`! youtube chapter "${marks[i - 1].label}" is under 10 s (${tl.lang})`);
  const x = L[textLang];
  return [script.hook[textLang], '', script.about[textLang], '', `${x.chapters}:`, ...marks.map((m) => `${mmss(m.t)} ${m.label}`), '',
    `${x.docs}: ${docsUrl}`, `${x.code}: ${codeUrl}`, '', x.rec(lastTag)].join('\n');
}

let md = `# Chapter ${N} — ${script.title.en}\n\nNothing is uploaded by the pipeline: these are the texts for the owner's review.\n`;
for (const lang of ['en', 'es']) {
  const tlPath = resolve(here, `build/ch${chapter}/${lang}/timeline.json`);
  if (!existsSync(tlPath)) { md += `\n## ${L[lang].master}\n\n(not rendered yet)\n`; continue; }
  const tl = json(tlPath), other = lang === 'en' ? 'es' : 'en';
  md += `
## ${L[lang].master} — \`ch${chapter}-${lang}.mp4\` (${mmss(tl.duration)})

- **Title (EN):** EventConductor · ${N}. ${script.title.en} (${mmss(tl.duration)})
- **Title (ES):** EventConductor · ${N}. ${script.title.es} (${mmss(tl.duration)})
- **${L.en.audio}:** ${L[lang].lang} · voice ${tl.voice} (${tl.engine})
- **${L.en.subs}:** \`ch${chapter}-${lang}.srt\` (${lang.toUpperCase()}), \`ch${chapter}-${lang}.${other}.srt\` (${other.toUpperCase()}, timed to this master)
- **${L.en.playlist}:** EventConductor in N minutes
- **${L.en.vis}:** unlisted
- **Thumbnail:** \`ch${chapter}-thumb.png\`

### Description (EN)${lang === 'es' ? ' — YouTube translation of this video' : ''}

\`\`\`
${description('en', tl)}
\`\`\`

### Description (ES)${lang === 'en' ? ' — YouTube translation of this video' : ''}

\`\`\`
${description('es', tl)}
\`\`\`
`;
}
writeFileSync(resolve(out, `ch${chapter}-youtube.md`), md);
console.log('youtube', resolve(out, `ch${chapter}-youtube.md`));

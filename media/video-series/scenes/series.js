// The series compositor runtime. window.render(t) draws the master at t seconds — nothing moves on its
// own, so rendering frame by frame (scripts/render-video.mjs) is deterministic.
// window.DATA (built by render-video.mjs):
//   { chapter, lang, strings, logo, duration,
//     scenes: [{ id, view, spec, start, dur, cues: [{start, end, text}] }],
//     code: { <scene>: { file, first, lines[] } },   // real file excerpts
//     term: { <id>: { cmd, cwd, lines[] } },          // real command output
//     live: { <scene>: { dir, frames: [{ f, cam }] } } }  // real console frames
// Chapter views register in window.VIEWS before this file's boot() runs (chapters/NN/scenes.js).
(() => {
  const D = window.DATA;
  const $ = (sel, root = document) => root.querySelector(sel);
  const clamp = (v, a = 0, b = 1) => Math.max(a, Math.min(b, v));
  const seg = (t, a, b) => clamp((t - a) / (b - a));
  const lerp = (a, b, p) => a + (b - a) * p;
  const eio = (p) => (p < 0.5 ? 4 * p * p * p : 1 - Math.pow(-2 * p + 2, 3) / 2);
  const eout = (p) => 1 - Math.pow(1 - p, 3);
  const esc = (s) => String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  const el = (tag, cls, html, parent) => { const e = document.createElement(tag); if (cls) e.className = cls; if (html != null) e.innerHTML = html; if (parent) parent.appendChild(e); return e; };
  const css = (e, o) => { for (const k in o) e.style[k] = typeof o[k] === 'number' && !['opacity', 'zIndex'].includes(k) ? o[k] + 'px' : o[k]; return e; };
  /** On-screen text of the master's language. Inline **x** → amber, `x` → azure mono. */
  const S = (key) => { const v = D.strings[key]; if (v == null) throw new Error(`missing string ${key} (${D.lang})`); return v; };
  const rich = (s) => esc(s).replace(/\*\*(.+?)\*\*/g, '<b class="amber">$1</b>').replace(/`(.+?)`/g, '<span class="accent" style="font-family:var(--mono)">$1</span>');
  /** A time inside a scene: seconds, 'c2' (cue 2 starts), 'e2' (cue 2 ends), 'end', with +/- offsets. */
  const at = (sc, spec) => {
    if (typeof spec === 'number') return spec;
    const m = String(spec).match(/^(c|e|end)(-?\d+)?([+-][\d.]+)?$/);
    if (!m) throw new Error(`bad time ${spec}`);
    const off = m[3] ? parseFloat(m[3]) : 0;
    if (m[1] === 'end') return sc.dur + off;
    let i = parseInt(m[2], 10); if (i < 0) i += sc.cues.length;
    const cue = sc.cues[clamp(i, 0, sc.cues.length - 1)];
    if (!cue) return off;
    return (m[1] === 'c' ? cue.start : cue.end) + off;
  };
  /** 0→1 as something appears over [a, a+fi] and back to 0 over [b-fo, b]. */
  const vis = (t, a, b = 1e9, fi = 0.4, fo = 0.4) => Math.min(seg(t, a, a + fi), 1 - seg(t, b - fo, b));
  const pending = [];

  /* ---------- syntax colouring (display only; the text is the real file) ---------- */
  /** Single-pass tokenizer: rules are [class, regex]; text between matches is escaped as is. */
  function tokens(line, rules) {
    const re = new RegExp(rules.map(([, r]) => `(${r.source})`).join('|'), 'g');
    let out = '', last = 0, m;
    while ((m = re.exec(line))) {
      if (m[0] === '') { re.lastIndex++; continue; }
      const k = rules[m.slice(1).findIndex((g) => g !== undefined)][0];
      out += esc(line.slice(last, m.index)) + (k ? `<span class="${k}">${esc(m[0])}</span>` : esc(m[0]));
      last = m.index + m[0].length;
    }
    return out + esc(line.slice(last));
  }
  const YAML = [['tk-c', /(?:^|(?<=\s))#.*$/], ['tk-key', /^\s*-?\s*[A-Za-z_][\w-]*(?=:)/], ['tk-s', /"(?:[^"\\]|\\.)*"|'[^']*'/],
    ['tk-k', /\b(?:[A-Z][A-Z_]{2,}|true|false)\b/], ['tk-n', /\bP?T?\d+[DHMS]?\b/], ['tk-p', /[:\[\]{},-]/]];
  const JAVA = [['tk-c', /\/\/.*$|\/\*.*?\*\/|^\s*\*.*$|\/\*\*.*$/], ['tk-s', /"(?:[^"\\]|\\.)*"/], ['tk-an', /@\w+/],
    ['tk-k', /\b(?:public|private|static|final|record|class|new|return|throw|var|if|else|void|import|package|true|false|null)\b/],
    ['tk-ty', /\b[A-Z][A-Za-z0-9_]*\b/], ['tk-n', /\b\d+\b/]];
  const hlYaml = (line) => tokens(line, YAML);
  const hlJava = (line) => tokens(line, JAVA);
  const hl = (file, line) => (/\.(ya?ml|ec)$/.test(file) ? hlYaml(line) : /\.java$/.test(file) ? hlJava(line) : esc(line));

  function windowEl(root, { x, y, w, h, title, cls = '' }) {
    const win = el('div', 'win ' + cls, null, root);
    css(win, { left: x, top: y, width: w, height: h });
    const bar = el('div', 'bar', '<i style="background:#ff5f57"></i><i style="background:#febc2e"></i><i style="background:#28c840"></i>', win);
    el('span', 'fn', title, bar);
    const body = el('div', 'body', null, win);
    return { win, body, bh: h - 52, bw: w };
  }

  /* ---------- a terminal that types a real command and prints its real output ---------- */
  function terminal(body, sc, items, { cps = 42, lineGap = 0.045 } = {}) {
    const box = el('div', 'term', null, body);
    const runs = items.map((it) => ({ ...it, data: D.term[it.id] }));
    for (const r of runs) if (!r.data) throw new Error(`no terminal output ${r.id} — run record-live first`);
    return (t) => {
      let html = '';
      let cursorAt = null;
      for (const r of runs) {
        const t0 = at(sc, r.at ?? 0.3);
        if (t < t0) break;
        const cmd = r.data.cmd, typed = Math.floor((t - t0) * cps);
        const shown = cmd.slice(0, typed);
        html += `<span class="ps">${esc(r.prompt ?? D.prompt ?? '~/workshop-hotel-booking $')}</span> <span class="cmd">${esc(shown)}</span>`;
        if (typed < cmd.length) { cursorAt = true; html += '<span class="cur"></span>'; break; }
        html += '\n';
        const tOut = t0 + cmd.length / cps + (r.wait ?? 0.35);
        const n = t < tOut ? 0 : Math.floor((t - tOut) / lineGap) + 1;
        const lines = r.data.lines.slice(0, n).map((l) => {
          const c = /BUILD SUCCESS|Tests run:.*Failures: 0, Errors: 0|PASS/.test(l) ? 'ok' : /BUILD FAILURE|ERROR|FAIL/.test(l) ? 'bad' : 'out';
          return `<span class="${c}">${esc(r.trim ? r.trim(l) : l)}</span>`;
        });
        html += lines.join('\n') + (lines.length ? '\n' : '');
      }
      if (!cursorAt && runs.length && t >= at(sc, runs[0].at ?? 0.3)) html += `<span class="ps">${esc(runs[runs.length - 1].prompt ?? D.prompt ?? '~/workshop-hotel-booking $')}</span> <span class="cur"></span>`;
      box.innerHTML = html;
      const over = box.scrollHeight - body.clientHeight;          // keep the newest line in view
      box.style.transform = over > 0 ? `translateY(${-over}px)` : '';
    };
  }

  /* ---------- views ---------- */
  const VIEWS = (window.VIEWS = Object.assign({
    /** A real file, line numbers kept, emphasis per narration cue. spec: { title, emphasis: [{at, match: [...]}], reveal: {from, to}, notes, term } */
    code(root, sc) {
      const c = D.code[sc.id], spec = sc.spec;
      const hasSide = !!(spec.notes || (spec.term && !spec.term.geom));
      const g = spec.geom || { x: 90, y: 64, w: hasSide ? 1130 : 1740, h: 952 };
      const { body, bh } = windowEl(root, { ...g, title: `<b>${esc(c.file.split('/').pop())}</b>  ·  ${esc(c.file.split('/').slice(0, -1).join('/').replace(/\/src\/(main|test)\/(resources|java)\/.*$/, '/src/$1/…'))}` });
      const code = el('div', 'code', null, body);
      if (spec.fontSize) css(code, { fontSize: spec.fontSize });
      const LH = 38;
      const lineEls = c.lines.map((l, i) => el('span', 'l', `<span class="n">${c.first + i}</span>${hl(c.file, l)}`, code));
      const marks = (spec.emphasis || []).map((e) => ({ t: at(sc, e.at), set: new Set(c.lines.map((l, i) => (e.match.some((m) => new RegExp(m).test(l)) || (e.lines || []).some(([a, b]) => c.first + i >= a && c.first + i <= b) ? i : -1)).filter((i) => i >= 0)) }));
      for (const m of marks) if (!m.set.size) throw new Error(`${sc.id}: emphasis matches nothing`);
      const maxScroll = Math.max(0, c.lines.length * LH + 40 - bh);
      const scrollFor = (set) => { if (!set || !set.size) return 0; const ix = [...set]; const mid = ((Math.min(...ix) + Math.max(...ix) + 1) / 2) * LH + 20; return clamp(mid - bh / 2, 0, maxScroll); };
      const keys = [{ t: 0, s: spec.startScroll != null ? clamp(spec.startScroll * LH, 0, maxScroll) : 0 }, ...marks.map((m) => ({ t: m.t, s: scrollFor(m.set) }))];
      let side = null;
      if (hasSide) {
        side = el('div', 'abs', null, root); css(side, { left: g.x + g.w + 60, top: g.y + 10, width: 1920 - (g.x + g.w + 60) - 80 });
      }
      const notes = (spec.notes || []).map((n) => { const e = el('div', 'note', rich(S(n.key)), side); css(e, { position: 'relative', marginBottom: '34px' }); return { e, t: at(sc, n.at) }; });
      let termDraw = null, termWin = null;
      if (spec.term) {
        const tw = windowEl(root, { ...(spec.term.geom || { x: g.x + g.w + 40, y: 330, w: 1920 - (g.x + g.w + 40) - 60, h: 560 }), title: 'terminal' });
        termWin = tw.win; termDraw = terminal(tw.body, sc, spec.term.items, spec.term.opts);
        termWin.querySelector('.term').style.fontSize = '21px';
      }
      const pills = pillEls(root, sc);   // labels over a code scene too (spec.pills)
      return (t) => {
        pills(t);
        let cur = null; for (const m of marks) if (t >= m.t) cur = m;
        const reveal = spec.reveal ? Math.floor(lerp(0, c.lines.length, seg(t, at(sc, spec.reveal.from), at(sc, spec.reveal.to)))) : c.lines.length;
        lineEls.forEach((e, i) => { e.className = 'l' + (cur ? (cur.set.has(i) ? ' em' : ' dim') : ''); e.style.visibility = i < reveal ? 'visible' : 'hidden'; });
        let s = keys[0].s;
        for (let i = 1; i < keys.length; i++) if (t >= keys[i].t) s = lerp(keys[i - 1].s === undefined ? 0 : s, keys[i].s, eio(seg(t, keys[i].t, keys[i].t + 0.9)));
        if (spec.reveal) s = clamp(Math.max(s, (reveal + 2) * LH - bh), 0, maxScroll);
        code.style.transform = `translateY(${-s}px)`;
        for (const n of notes) { const v = vis(t, n.t, 1e9, 0.5); n.e.style.opacity = v; n.e.style.transform = `translateY(${(1 - eout(v)) * 14}px)`; }
        if (termWin) { const t0 = at(sc, spec.term.from ?? 0); termWin.style.opacity = vis(t, t0, 1e9, 0.4); termDraw(t); }
      };
    },

    /** Real command(s) typed in a terminal, real output. spec: { items: [{id, at}], title, pill } */
    terminal(root, sc) {
      const spec = sc.spec, g = spec.geom || { x: 160, y: 120, w: 1600, h: 780 };
      const { body } = windowEl(root, { ...g, title: spec.title || 'terminal' });
      const draw = terminal(body, sc, spec.items, spec.opts);
      const pills = pillEls(root, sc);
      return (t) => { draw(t); pills(t); };
    },

    /** The real console, filmed frame by frame; the camera was keyed by the recorder. spec: { pills, terms } */
    live(root, sc) {
      const L = D.live[sc.id];
      if (!L) throw new Error(`no live frames for ${sc.id} — run record-live first`);
      const g = { x: 88, y: 22, w: 1744, h: 1036 };
      const { body, bw, bh } = windowEl(root, { ...g, title: `<b>${esc(sc.spec.host ?? D.host ?? 'localhost:8095')}</b>  ·  ${esc(sc.spec.app ?? 'EventConductor console')}`, cls: 'console' });
      const img = el('img', 'frame', null, body);
      const VPW = L.viewport?.w ?? 1920, VPH = L.viewport?.h ?? 1080;
      css(img, { width: VPW, height: VPH });
      const base = bw / VPW;
      const terms = (sc.spec.terms || []).map((tm) => {
        const w = windowEl(root, { x: tm.x ?? 150, y: tm.y ?? 520, w: tm.w ?? 1200, h: tm.h ?? 440, title: 'terminal' });
        w.win.querySelector('.body').style.background = 'rgba(8,19,25,.97)';
        return { win: w.win, draw: terminal(w.body, sc, tm.items, tm.opts), from: at(sc, tm.from ?? 0), to: at(sc, tm.to ?? 'end') };
      });
      const pills = pillEls(root, sc);
      let last = null;
      return (t) => {
        const i = clamp(Math.floor(t * 30 + 1e-6), 0, L.frames.length - 1);
        const fr = L.frames[i];
        const src = `${L.dir}/${fr.f}`;
        if (src !== last) { img.src = src; last = src; pending.push(img.decode().catch(() => {})); }
        const k = base * fr.cam.z;
        img.style.transform = `translate(${bw / 2 - fr.cam.cx * k}px, ${bh / 2 - fr.cam.cy * k}px) scale(${k})`;
        for (const tm of terms) { const v = vis(t, tm.from, tm.to, 0.35, 0.35); tm.win.style.opacity = v; tm.win.style.visibility = v > 0 ? 'visible' : 'hidden'; tm.win.style.transform = `translateY(${(1 - eout(v)) * 30}px)`; if (v > 0) tm.draw(t); }
        pills(t);
      };
    },
  }, window.VIEWS || {}));

  /** Labels over a scene (stand-in notices, captions). spec.pills: [{key, from, to, x, y, cls}] */
  function pillEls(root, sc) {
    const ps = (sc.spec.pills || []).map((p) => { const e = el('div', 'pill ' + (p.cls || ''), rich(S(p.key)), root); css(e, { left: p.x ?? 'auto', right: p.right ?? 'auto', top: p.y ?? 'auto', bottom: p.bottom ?? 'auto' }); return { e, a: at(sc, p.from ?? 0.4), b: at(sc, p.to ?? 'end') }; });
    return (t) => { for (const p of ps) { const v = vis(t, p.a, p.b); p.e.style.opacity = v; p.e.style.transform = `translateY(${(1 - eout(v)) * 12}px)`; } };
  }

  /* ---------- boot ---------- */
  const stage = $('#stage');
  const H = { $, el, css, clamp, seg, lerp, eio, eout, esc, vis, at, S, rich, windowEl, terminal, pillEls, D };
  window.H = H;
  const built = D.scenes.map((sc) => {
    const root = el('section', 'scene', null, stage);
    root.dataset.id = sc.id;
    const view = VIEWS[sc.view];
    if (!view) throw new Error(`no view ${sc.view} for scene ${sc.id}`);
    return { sc, root, draw: view(root, sc, H) };
  });
  const FADE = 0.4;
  window.DURATION = D.duration;
  window.render = (T) => {
    pending.length = 0;
    for (const b of built) {
      const { sc } = b, t = T - sc.start;
      const first = sc === D.scenes[0], lastSc = sc === D.scenes[D.scenes.length - 1];
      const o = Math.min(first ? (t >= 0 ? 1 : 0) : seg(t, -FADE / 2, FADE / 2), lastSc ? (t <= sc.dur ? 1 : 0) : 1 - seg(t, sc.dur - FADE / 2, sc.dur + FADE / 2));
      b.root.style.opacity = o;
      b.root.style.visibility = o > 0 ? 'visible' : 'hidden';
      if (o > 0) b.draw(clamp(t, 0, sc.dur));
    }
    window.pending = Promise.all(pending);
  };
  window.ready = document.fonts.ready;
})();

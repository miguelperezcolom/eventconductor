// Chapter 5's own HTML scenes (in the reel's language). Real product footage is in live.mjs.

/** A heading and rows "name | rule"; row i appears with narration cue cueOf[i]. */
function ruleRows(root, sc, H, headKey, itemsKey, cueOf) {
  const { el, css, S, vis, eout, esc, rich, at } = H;
  const head = el('h2', null, esc(S(headKey)), root); css(head, { position: 'absolute', left: 150, top: 110, fontSize: 60 });
  const list = el('div', 'abs', null, root); css(list, { left: 150, top: 250, width: 1620, display: 'grid', gap: '22px' });
  const rows = S(itemsKey).map(([k, v]) => {
    const r = el('div', 'card', null, list); css(r, { position: 'relative', display: 'grid', gridTemplateColumns: '460px 1fr', alignItems: 'center', padding: '26px 40px' });
    const a = el('div', null, esc(k), r); css(a, { fontFamily: 'var(--mono)', fontWeight: 700, fontSize: 34, color: 'var(--azure)' });
    const b = el('div', 'lead', rich(v), r); css(b, { fontSize: 32, color: 'var(--ink)' });
    return r;
  });
  return (t) => {
    head.style.opacity = vis(t, 0.1, 1e9);
    rows.forEach((r, i) => { const c = cueOf[i] ?? i; const t0 = at(sc, 'c' + c) + (cueOf.slice(0, i).filter((x) => x === c).length) * 1.6; const v = vis(t, t0, 1e9, 0.45); r.style.opacity = v; r.style.transform = `translateY(${(1 - eout(v)) * 22}px)`; });
  };
}
window.VIEWS = Object.assign(window.VIEWS || {}, {
  /** Title visual: variables → request → the API → response → variables, drawn left to right. */
  wire(root, sc, H) {
    const { el, css, S, vis, eout, esc, at } = H;
    const row = el('div', 'abs', null, root); css(row, { left: 150, right: 150, top: 690, display: 'flex', alignItems: 'center', justifyContent: 'center', gap: '18px' });
    const parts = [];
    S('wire').forEach((txt, i) => {
      if (i) { const a = el('div', null, '→', row); css(a, { fontFamily: 'var(--mono)', fontSize: 40, color: 'var(--faint)' }); parts.push(a); }
      const c = el('span', 'chip ' + (i === 2 ? 'amb' : i % 2 ? 'az' : 'good'), esc(txt), row); css(c, { fontSize: 30 }); parts.push(c);
    });
    return (t) => { const a = at(sc, 'c1') + 0.3; parts.forEach((p, i) => { const v = vis(t, a + i * 0.22, 1e9, 0.4); p.style.opacity = v; p.style.transform = `translateY(${(1 - eout(v)) * 20}px)`; }); };
  },

  /** A list of rows (name → rule) revealed one per narration cue. Used by `rules` and `langs`. */
  rules(root, sc, H) { return ruleRows(root, sc, H, 'rules.head', 'rules', [0, 1, 2, 2, 3]); },
  langs(root, sc, H) {
    const draw = ruleRows(root, sc, H, 'langs.head', 'langs.items', [1, 1, 2]);
    const note = H.el('div', 'pill amber', H.rich(H.S('langs.note')), root); H.css(note, { left: 150, top: 880 });
    return (t) => { draw(t); note.style.opacity = H.vis(t, H.at(sc, 'c2'), 1e9, 0.5); };
  },

  /** Secrets: the one form an inline credential may take, and three facts about it. */
  secrets(root, sc, H) {
    const { el, css, S, vis, eout, esc, rich, at } = H;
    const head = el('div', 'eyebrow', esc(S('sec.head')), root); css(head, { position: 'absolute', left: 150, top: 130 });
    const code = el('div', 'card', `<span style="color:var(--azure)">auth</span>: ${esc(S('sec.code').replace(/^auth: /, ''))}`, root);
    css(code, { left: 150, top: 230, width: 1620, padding: '40px 50px', fontFamily: 'var(--mono)', fontSize: 40, color: 'var(--ink)' });
    const grid = el('div', 'abs', null, root); css(grid, { left: 150, top: 480, width: 1620, display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: '34px' });
    const cards = S('sec.items').map(([k, v], i) => {
      const c = el('div', 'card', null, grid); css(c, { position: 'relative', height: 300, padding: '40px 40px' });
      const a = el('div', null, esc(k), c); css(a, { fontFamily: 'var(--mono)', fontWeight: 700, fontSize: 38, color: i === 2 ? 'var(--amber)' : 'var(--ink)' });
      const b = el('div', 'lead', rich(v), c); css(b, { marginTop: 24, fontSize: 32 });
      return c;
    });
    return (t) => {
      head.style.opacity = vis(t, 0.1, 1e9); const v0 = vis(t, 0.3, 1e9, 0.5); code.style.opacity = v0; code.style.transform = `translateY(${(1 - eout(v0)) * 20}px)`;
      const ts = [at(sc, 'c1'), at(sc, 'c1') + 2.2, at(sc, 'c2')];
      cards.forEach((c, i) => { const v = vis(t, ts[i], 1e9, 0.5); c.style.opacity = v; c.style.transform = `translateY(${(1 - eout(v)) * 26}px)`; });
    };
  },
});


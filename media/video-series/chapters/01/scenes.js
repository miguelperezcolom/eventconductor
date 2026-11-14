// Chapter 1's own HTML scenes (idea, plumbing, topologies), in the reel's language.
window.VIEWS = Object.assign(window.VIEWS || {}, {
  /** Cold open: the mark draws in, the wordmark, the tagline, then the hook. */
  hook(root, sc, H) {
    const { el, css, S, rich, vis, eout, at, D } = H;
    const box = el('div', 'abs', null, root); css(box, { left: 0, right: 0, top: 170, textAlign: 'center' });
    const logo = el('div', null, `<svg class="mark" width="190" height="190" viewBox="0 0 128 128">${D.logo}</svg>`, box);
    css(logo, { filter: 'drop-shadow(0 0 46px rgba(246,178,60,.5))' });
    const word = el('h1', null, 'EventConductor', box); css(word, { fontSize: 120, marginTop: 34, letterSpacing: '.02em' });
    const tag = el('div', 'lead', rich(S('tagline')), box); css(tag, { marginTop: 26, fontFamily: 'var(--mono)', fontSize: 40 });
    const hook = el('h2', null, rich(S('hook')), root); css(hook, { position: 'absolute', left: 160, right: 160, top: 420, fontSize: 84, textAlign: 'center' });
    return (t) => {
      const h1 = at(sc, 'c1');
      const vHook = Math.min(vis(t, 0.2, h1 - 0.1, 0.5, 0.5), 1);
      hook.style.opacity = vHook; hook.style.transform = `translateY(${(1 - eout(vis(t, 0.2, 1e9, 0.6))) * 24}px)`;
      [[logo, h1], [word, h1 + 0.35], [tag, h1 + 1.2]].forEach(([e, a]) => { const v = vis(t, a, 1e9, 0.6); e.style.opacity = v; e.style.transform = `translateY(${(1 - eout(v)) * 22}px) scale(${0.96 + 0.04 * eout(v)})`; });
    };
  },

  /** Three business steps (green), then the plumbing production adds (pink), rising beneath. */
  plumbing(root, sc, H) {
    const { el, css, S, vis, eout, at, esc } = H;
    const h1 = el('h2', null, esc(S('biz.head')), root); css(h1, { position: 'absolute', left: 0, right: 0, top: 110, textAlign: 'center', fontSize: 64 });
    const row = el('div', 'abs', null, root); css(row, { left: 0, right: 0, top: 240, display: 'flex', justifyContent: 'center', gap: '30px' });
    const biz = S('biz').map((x) => { const e = el('span', 'chip good', esc(x), row); css(e, { fontSize: 40, padding: '20px 36px' }); return e; });
    const line = el('div', 'abs', null, root); css(line, { left: 200, right: 200, top: 400, height: 2, background: 'linear-gradient(90deg, transparent, var(--azure-dim), transparent)' });
    const h2 = el('div', 'eyebrow', esc(S('plumb.head')), root); css(h2, { position: 'absolute', left: 0, right: 0, top: 460, justifyContent: 'center', color: 'var(--pink)' });
    const grid = el('div', 'abs', null, root); css(grid, { left: 160, right: 160, top: 560, display: 'flex', flexWrap: 'wrap', justifyContent: 'center', gap: '26px 26px' });
    const pl = S('plumb').map((x) => { const e = el('span', 'chip bad', esc(x), grid); css(e, { fontSize: 36, padding: '18px 32px' }); return e; });
    return (t) => {
      const v = vis(t, 0.1, 1e9, 0.5); h1.style.opacity = v;
      biz.forEach((e, i) => { const w = vis(t, 0.8 + i * 0.9, 1e9, 0.45); e.style.opacity = w; e.style.transform = `translateY(${(1 - eout(w)) * 26}px)`; });
      const a = at(sc, 'c1'); line.style.opacity = vis(t, a, 1e9); h2.style.opacity = vis(t, a, 1e9);
      const b = at(sc, 'c2'), e2 = at(sc, 'e2') - 1.0;
      pl.forEach((e, i) => { const ti = b + ((e2 - b) * i) / (pl.length - 1); const w = vis(t, ti, 1e9, 0.45); e.style.opacity = w; e.style.transform = `translateY(${(1 - eout(w)) * 40}px)`; });
    };
  },

  /** Same definitions, every topology: embedded memory → embedded JPA → distributed Kafka → Helm. */
  topologies(root, sc, H) {
    const { el, css, S, vis, eout, at, esc } = H;
    const head = el('div', 'eyebrow', esc(S('topo.head')), root); css(head, { position: 'absolute', left: 150, top: 150 });
    const grid = el('div', 'abs', null, root); css(grid, { left: 150, top: 260, width: 1620, display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: '30px' });
    const cards = S('topo').map(([kind, what, line], i) => {
      const c = el('div', 'card', null, grid); css(c, { position: 'relative', height: 470, padding: '40px 34px' });
      const k = el('div', 'eyebrow', esc(kind), c); css(k, { fontSize: 22, color: i < 2 ? 'var(--azure)' : 'var(--amber)' });
      const w = el('div', null, esc(what), c); css(w, { fontFamily: 'var(--mono)', fontWeight: 700, fontSize: 52, marginTop: 34 });
      const l = el('div', 'lead', esc(line), c); css(l, { marginTop: 30, fontSize: 30 });
      return c;
    });
    const arrow = el('div', 'abs', null, root); css(arrow, { left: 150, top: 790, width: 1620, height: 4, background: 'linear-gradient(90deg, var(--azure), var(--amber))', transformOrigin: '0 50%' });
    const defs = el('div', 'note', H.rich(S('topo.defs')), root); css(defs, { left: 150, top: 830 });
    return (t) => {
      head.style.opacity = vis(t, 0.1, 1e9);
      const b = at(sc, 'c1');
      cards.forEach((c, i) => { const ti = i < 2 ? 0.4 + i * 1.0 : b + 0.5 + (i - 2) * 2.2; const v = vis(t, ti, 1e9, 0.5); c.style.opacity = v; c.style.transform = `translateY(${(1 - eout(v)) * 30}px)`; });
      const p = H.seg(t, 0.4, at(sc, 'e1')); arrow.style.transform = `scaleX(${p})`;
      defs.style.opacity = vis(t, 1.4, 1e9);
    };
  },
});

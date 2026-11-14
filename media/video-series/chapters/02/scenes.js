// Chapter 2's own HTML scenes (in the reel's language). Real product footage is in live.mjs.
window.VIEWS = Object.assign(window.VIEWS || {}, {
  /** Title visual: the five pieces of a first process, rising one by one (the real file names). */
  pieces(root, sc, H) {
    const { el, css, S, vis, eout, esc } = H;
    const row = el('div', 'abs', null, root); css(row, { left: 150, right: 150, top: 660, display: 'flex', flexWrap: 'wrap', gap: '22px' });
    const items = S('pieces').map(([file, what], i) => {
      const c = el('div', 'card', null, row); css(c, { position: 'relative', padding: '26px 32px' });
      const f = el('div', null, esc(file), c); css(f, { fontFamily: 'var(--mono)', fontSize: 26, color: 'var(--azure)' });
      const w = el('div', 'lead', esc(what), c); css(w, { fontSize: 28, marginTop: 8 });
      return { c, i };
    });
    return (t) => {
      const a = H.at(sc, 'c1') + 0.6;
      for (const k of items) { const v = vis(t, a + k.i * 0.7, 1e9, 0.5); k.c.style.opacity = v; k.c.style.transform = `translateY(${(1 - eout(v)) * 26}px)`; }
    };
  },

  /** Recap: declare · implement · run. */
  recap(root, sc, H) {
    const { el, css, S, vis, eout, esc, at } = H;
    const grid = el('div', 'abs', null, root); css(grid, { left: 150, top: 250, width: 1620, display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: '40px' });
    const cards = S('recap.items').map(([verb, line, ex], i) => {
      const c = el('div', 'card', null, grid); css(c, { position: 'relative', height: 420, padding: '48px 44px' });
      const v = el('div', null, esc(verb), c); css(v, { fontFamily: 'var(--mono)', fontWeight: 700, fontSize: 64, color: ['var(--azure)', 'var(--good)', 'var(--amber)'][i] });
      const l = el('div', 'lead', esc(line), c); css(l, { marginTop: 30, fontSize: 36, color: 'var(--ink)' });
      const e = el('div', null, esc(ex), c); css(e, { position: 'absolute', left: 44, bottom: 44, fontFamily: 'var(--mono)', fontSize: 24, color: 'var(--muted)' });
      return c;
    });
    return (t) => {
      const b = at(sc, 'c1');
      cards.forEach((c, i) => { const v = vis(t, (i === 0 ? 0.3 : b) + (i === 0 ? 0 : (i - 1) * 1.6) + (i === 0 ? 0 : 0.2), 1e9, 0.5); c.style.opacity = v; c.style.transform = `translateY(${(1 - eout(v)) * 30}px)`; });
    };
  },
});

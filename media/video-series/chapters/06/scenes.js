// Chapter 6's own HTML scenes (in the reel's language). Real product footage is in live.mjs.
window.VIEWS = Object.assign(window.VIEWS || {}, {
  /** Title visual: the control-flow vocabulary of the chapter, one chip per construct. */
  shapes(root, sc, H) {
    const { el, css, S, vis, eout, esc, at } = H;
    const row = el('div', 'abs', null, root); css(row, { left: 150, right: 150, top: 640, display: 'flex', flexWrap: 'wrap', gap: '20px' });
    const cols = ['amb', 'az', 'az', 'good', 'good', 'good', 'amb'];
    const chips = S('shapes').map(([k, v], i) => {
      const c = el('div', 'card', null, row); css(c, { position: 'relative', padding: '22px 30px' });
      const a = el('div', null, esc(k), c); css(a, { fontFamily: 'var(--mono)', fontWeight: 700, fontSize: 32, color: cols[i] === 'amb' ? 'var(--amber)' : cols[i] === 'az' ? 'var(--azure)' : 'var(--good)' });
      const b = el('div', 'lead', esc(v), c); css(b, { fontSize: 24, marginTop: 6 });
      return c;
    });
    return (t) => { const a = at(sc, 'c1'); chips.forEach((c, i) => { const v = vis(t, a + i * 0.45, 1e9, 0.45); c.style.opacity = v; c.style.transform = `translateY(${(1 - eout(v)) * 22}px)`; }); };
  },
});

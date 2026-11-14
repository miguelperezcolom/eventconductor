// Chapter 3's own HTML scenes (in the reel's language). Real product footage is in live.mjs
// (Visual Studio Code itself, through Playwright Electron, and the console).
window.VIEWS = Object.assign(window.VIEWS || {}, {
  /** Where definitions are edited and checked: three cards. IntelliJ is named, not filmed. */
  ides(root, sc, H) {
    const { el, css, S, vis, eout, esc, rich, at } = H;
    const grid = el('div', 'abs', null, root); css(grid, { left: 150, top: 230, width: 1620, display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: '36px' });
    const cards = S('ides.items').map(([k, v], i) => {
      const c = el('div', 'card', null, grid); css(c, { position: 'relative', height: 380, padding: '44px 40px' });
      const a = el('div', null, esc(k), c); css(a, { fontFamily: 'var(--mono)', fontWeight: 700, fontSize: 42, color: ['var(--azure)', 'var(--violet)', 'var(--amber)'][i] });
      const b = el('div', 'lead', rich(v), c); css(b, { marginTop: 28, fontSize: 32, color: 'var(--ink)' });
      return c;
    });
    const note = el('div', 'pill', esc(S('ides.note')), root); css(note, { left: 150, top: 700 });
    return (t) => {
      const ts = [0.3, 0.7, at(sc, 'c1')];
      cards.forEach((c, i) => { const v = vis(t, ts[i], 1e9, 0.5); c.style.opacity = v; c.style.transform = `translateY(${(1 - eout(v)) * 26}px)`; });
      note.style.opacity = vis(t, 1.2, 1e9, 0.5);
    };
  },
});

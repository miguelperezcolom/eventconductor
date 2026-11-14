// Chapter 8's own HTML scenes (in the reel's language). Real product footage is in live.mjs.
window.VIEWS = Object.assign(window.VIEWS || {}, {
  /** Title visual: the three waits of the chapter, one per narration cue. */
  moments(root, sc, H) {
    const { el, css, S, vis, eout, esc, at } = H;
    const row = el('div', 'abs', null, root); css(row, { left: 150, right: 150, top: 640, display: 'flex', gap: '28px', justifyContent: 'center' });
    const cards = S('moments').map((txt, i) => {
      const c = el('div', 'card', null, row); css(c, { position: 'relative', padding: '34px 40px', width: 500 });
      const ic = el('div', null, ['⏱', '✉', '✉'][i], c); css(ic, { fontSize: 44, color: i ? 'var(--azure)' : 'var(--amber)' });
      const t = el('div', null, esc(txt), c); css(t, { fontFamily: 'var(--mono)', fontSize: 30, marginTop: 14, color: 'var(--ink)' });
      return c;
    });
    return (t) => cards.forEach((c, i) => { const v = vis(t, at(sc, 'c' + i), 1e9, 0.5); c.style.opacity = v; c.style.transform = `translateY(${(1 - eout(v)) * 24}px)`; });
  },
});

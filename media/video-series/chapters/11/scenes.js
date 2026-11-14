// Chapter 11's own HTML scenes (diagrams, in the reel's language). Real product footage is in live.mjs.
window.VIEWS = Object.assign(window.VIEWS || {}, {
  /** Title visual: a row of chips joined by arrows, one lit per narration cue. strings: flow = [[label, cls, cue]] */
  flow(root, sc, H) {
    const { el, css, S, vis, eout, at, esc } = H;
    const row = el('div', 'abs', null, root); css(row, { left: 0, right: 0, top: 690, display: 'flex', justifyContent: 'center', alignItems: 'center', gap: '16px' });
    const parts = [];
    S('flow').forEach(([label, cls, cue], i) => {
      if (i) { const a = el('span', null, '→', row); css(a, { fontFamily: 'var(--mono)', fontSize: 36, color: 'var(--faint)' }); parts.push({ e: a, cue }); }
      const c = el('span', `chip ${cls}`, esc(label), row); css(c, { fontSize: 28, padding: '14px 24px' });
      parts.push({ e: c, cue });
    });
    return (t) => {
      for (const p of parts) { const v = vis(t, at(sc, p.cue) + 0.2, 1e9, 0.5); p.e.style.opacity = v; p.e.style.transform = `translateY(${(1 - eout(v)) * 24}px)`; }
    };
  },

  /** Recap: cards in a grid, each appearing at its cue; an optional strip below. spec: { head, items, cols, strip, stripAt } */
  cards(root, sc, H) {
    const { el, css, S, vis, eout, at, esc, rich } = H;
    const spec = sc.spec;
    const head = el('div', 'eyebrow', esc(S(spec.head)), root); css(head, { position: 'absolute', left: 150, top: 110 });
    const grid = el('div', 'abs', null, root);
    css(grid, { left: 150, top: 190, width: 1620, display: 'grid', gridTemplateColumns: `repeat(${spec.cols ?? 2}, 1fr)`, gap: '30px' });
    const cards = S(spec.items).map(([name, tag, line, cue]) => {
      const c = el('div', 'card', null, grid); css(c, { position: 'relative', minHeight: spec.cardH ?? 230, padding: '34px 40px' });
      const n = el('div', null, esc(name), c); css(n, { fontFamily: 'var(--mono)', fontWeight: 700, fontSize: 44 });
      if (tag) { const tg = el('span', 'chip az', esc(tag), c); css(tg, { position: 'absolute', right: 30, top: 30, fontSize: 22, padding: '6px 16px' }); }
      const l = el('div', 'lead', rich(line), c); css(l, { marginTop: 20, fontSize: 30 });
      return { c, cue };
    });
    let strip = null;
    if (spec.strip) {
      strip = el('div', 'card', null, root); css(strip, { left: 150, top: spec.stripTop ?? 760, width: 1620, padding: '30px 44px' });
      strip.innerHTML = S(spec.strip).map((s) => `<div class="note" style="position:relative;margin:6px 0">${rich(s)}</div>`).join('');
    }
    return (t) => {
      head.style.opacity = vis(t, 0.1, 1e9, 0.5);
      for (const k of cards) { const v = vis(t, at(sc, k.cue), 1e9, 0.5); k.c.style.opacity = v; k.c.style.transform = `translateY(${(1 - eout(v)) * 30}px)`; }
      if (strip) { const v = vis(t, at(sc, spec.stripAt), 1e9, 0.5); strip.style.opacity = v; strip.style.transform = `translateY(${(1 - eout(v)) * 20}px)`; }
    };
  },
});

// Chapter 7's own HTML scenes (in the reel's language). Real product footage is in live.mjs.
window.VIEWS = Object.assign(window.VIEWS || {}, {
  /** A timeline of a task nobody answers: now → +1 day (escalation) → +3 days (timeout). */
  clock(root, sc, H) {
    const { el, css, S, vis, eout, esc, rich, at, seg } = H;
    const line = el('div', 'abs', null, root); css(line, { left: 200, top: 420, width: 1520, height: 4, background: 'linear-gradient(90deg, var(--azure), var(--amber), var(--pink))', transformOrigin: '0 50%' });
    const pts = S('clock.items').map(([when, what], i) => {
      const x = 200 + i * 760;
      const dot = el('div', 'abs', null, root); css(dot, { left: x - 16, top: 406, width: 32, height: 32, borderRadius: '50%', background: ['var(--azure)', 'var(--amber)', 'var(--pink)'][i] });
      const box = el('div', 'abs', null, root); css(box, { left: x - (i === 2 ? 520 : i === 1 ? 260 : 0), top: 480, width: 520, textAlign: i === 2 ? 'right' : i === 1 ? 'center' : 'left' });
      const w = el('div', null, esc(when), box); css(w, { fontFamily: 'var(--mono)', fontWeight: 700, fontSize: 52, color: ['var(--azure)', 'var(--amber)', 'var(--pink)'][i] });
      const d = el('div', 'lead', rich(what), box); css(d, { marginTop: 16, fontSize: 32, color: 'var(--ink)' });
      return [dot, box];
    });
    const note = el('div', 'note', rich(S('clock.note')), root); css(note, { left: 200, top: 820, width: 1520 });
    return (t) => {
      const a = at(sc, 'c1'), b = at(sc, 'e1');
      line.style.transform = `scaleX(${seg(t, a - 0.6, b)})`;
      pts.forEach(([dot, box], i) => { const v = vis(t, i === 0 ? 0.3 : a + (i === 1 ? 0.8 : (b - a) * 0.6), 1e9, 0.5); dot.style.opacity = v; box.style.opacity = v; box.style.transform = `translateY(${(1 - eout(v)) * 20}px)`; });
      note.style.opacity = vis(t, at(sc, 'c2'), 1e9, 0.5);
    };
  },
});

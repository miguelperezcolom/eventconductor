// Chapter 4's own HTML scenes (diagrams, in the reel's language). Real product footage is in live.mjs.
window.VIEWS = Object.assign(window.VIEWS || {}, {
  /** Title visual: three room cards; two get booked, the penthouse is sold out, the booked two ask "undo?". */
  rooms(root, sc, H) {
    const { el, css, S, vis, eout, at, seg, lerp, eio } = H;
    const row = el('div', 'abs', null, root); css(row, { left: 0, right: 0, top: 560, display: 'flex', justifyContent: 'center', gap: '56px' });
    const cards = S('rooms.items').map(([num, type], i) => {
      const c = el('div', 'card', null, row);
      css(c, { position: 'relative', width: 380, height: 250, padding: '34px 40px' });
      el('div', 'eyebrow', type, c).style.fontSize = '22px';
      const n = el('div', null, num, c); css(n, { fontFamily: 'var(--mono)', fontWeight: 700, fontSize: 96, marginTop: 14 });
      const st = el('div', 'chip', '', c); css(st, { position: 'absolute', right: 30, bottom: 34, fontSize: 24, padding: '8px 18px' });
      return { c, st, i, bad: type === S('rooms.items')[2][1] };
    });
    return (t) => {
      const t1 = at(sc, 'c1'), t2 = at(sc, 'c2'), t3 = at(sc, 'c3');
      const down = eio(seg(t, t3 - 0.2, t3 + 0.8));
      row.style.transform = `translateY(${lerp(-160, 190, down)}px) scale(${lerp(1.15, 0.8, down)})`;
      for (const k of cards) {
        const v = vis(t, 0.15 + k.i * 0.25, 1e9, 0.5); k.c.style.opacity = v; k.c.style.transform = `translateY(${(1 - eout(v)) * 30}px)`;
        const decided = t >= t1 + 0.35 + k.i * 0.6;
        k.st.style.opacity = decided ? 1 : 0;
        if (k.bad) { k.st.className = 'chip bad'; k.st.textContent = S('rooms.soldout'); k.c.style.borderColor = decided ? 'rgba(255,117,151,.7)' : ''; }
        else if (t >= t2 + 0.2) { k.st.className = 'chip amb'; k.st.textContent = S('rooms.question'); k.c.style.borderColor = 'rgba(246,178,60,.7)'; }
        else { k.st.className = 'chip good'; k.st.textContent = S('rooms.booked'); k.c.style.borderColor = decided ? 'rgba(57,217,138,.6)' : ''; }
      }
    };
  },

  /** The hand-written version: a checklist that keeps growing. spec: { head, items, from, to } */
  checklist(root, sc, H) {
    const { el, css, S, vis, eout, at, rich } = H;
    const head = el('h2', null, rich(S(sc.spec.head)), root); css(head, { position: 'absolute', left: 150, top: 120, fontSize: 64 });
    const list = el('div', 'abs', null, root); css(list, { left: 150, top: 290, display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '28px 40px', width: 1620 });
    const items = S(sc.spec.items).map((txt, i) => {
      const e = el('div', 'chip bad', `<span style="opacity:.6">☐</span>&nbsp;&nbsp;${H.esc(txt)}`, list);
      css(e, { fontSize: 34, padding: '24px 34px', borderRadius: '20px', whiteSpace: 'normal' });
      return e;
    });
    return (t) => {
      const v = vis(t, 0.1, 1e9, 0.5); head.style.opacity = v; head.style.transform = `translateY(${(1 - eout(v)) * 20}px)`;
      const a = at(sc, sc.spec.from), b = at(sc, sc.spec.to) - 1.2;
      items.forEach((e, i) => { const ti = a + ((b - a) * i) / (items.length - 1); const w = vis(t, ti, 1e9, 0.4); e.style.opacity = w; e.style.transform = `translateY(${(1 - eout(w)) * 24}px)`; });
    };
  },

  /** The three onIterationFailure policies; Compensated is not Error; the 2.39 opt-ins. */
  policies(root, sc, H) {
    const { el, css, S, vis, eout, at, rich, esc } = H;
    const head = el('div', 'eyebrow', esc(S('pol.head')), root); css(head, { position: 'absolute', left: 150, top: 110 });
    const grid = el('div', 'abs', null, root); css(grid, { left: 150, top: 190, width: 1620, display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: '36px' });
    const cards = S('pol.items').map(([name, tag, line], i) => {
      const c = el('div', 'card', null, grid); css(c, { position: 'relative', height: 300, padding: '40px 40px' });
      const n = el('div', null, esc(name), c); css(n, { fontFamily: 'var(--mono)', fontWeight: 700, fontSize: 50, color: i === 2 ? 'var(--amber)' : 'var(--ink)' });
      if (tag) { const tg = el('span', 'chip az', esc(tag), c); css(tg, { position: 'absolute', right: 30, top: 30, fontSize: 22, padding: '6px 16px' }); }
      const l = el('div', 'lead', esc(line), c); css(l, { marginTop: 26, fontSize: 32 });
      return c;
    });
    const cmp = el('div', 'abs', null, root); css(cmp, { left: 150, top: 560, display: 'flex', alignItems: 'center', gap: '26px', fontFamily: 'var(--mono)', fontSize: 40 });
    cmp.innerHTML = `<span class="chip good" style="font-size:40px">${esc(S('pol.compensated'))}</span><span>${esc(S('pol.notError'))}</span><span class="chip bad" style="font-size:40px">${esc(S('pol.error'))}</span><span class="lead" style="font-size:32px">— ${esc(S('pol.notErrorTail'))}</span>`;
    const strip = el('div', 'card', null, root); css(strip, { left: 150, top: 720, width: 1620, padding: '34px 44px' });
    strip.innerHTML = `<div class="eyebrow" style="font-size:24px">${esc(S('pol.since'))}</div><div class="note" style="position:relative;margin-top:22px">${rich(S('pol.opt1'))}</div><div class="note" style="position:relative;margin-top:14px">${rich(S('pol.opt2'))}</div>`;
    return (t) => {
      const v0 = vis(t, 0.1, 1e9, 0.5); head.style.opacity = v0;
      cards.forEach((c, i) => {
        const v = vis(t, 0.3 + i * 0.25, 1e9, 0.5);
        const focus = t >= at(sc, 'c1') && t < at(sc, 'c3') ? (t >= at(sc, 'c2') ? 1 : 0) : t >= at(sc, 'c3') ? 2 : -1;
        const on = focus === -1 || focus === i;
        c.style.opacity = v * (on ? 1 : 0.4);
        c.style.borderColor = focus === i || (focus === -1 && i === 2) ? (i === 2 ? 'rgba(246,178,60,.7)' : 'var(--azure-dim)') : '';
        c.style.transform = `translateY(${(1 - eout(v)) * 30}px)`;
      });
      const v3 = vis(t, at(sc, 'c3'), 1e9, 0.5); cmp.style.opacity = v3; cmp.style.transform = `translateY(${(1 - eout(v3)) * 20}px)`;
      const v4 = vis(t, at(sc, 'c4'), 1e9, 0.5); strip.style.opacity = v4; strip.style.transform = `translateY(${(1 - eout(v4)) * 20}px)`;
    };
  },
});

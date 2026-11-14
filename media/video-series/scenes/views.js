// Views shared by every chapter: the title card, the close card and the thumbnail.
// A view is (root, scene, H) => draw(t); H is the helper kit from series.js.
window.VIEWS = Object.assign(window.VIEWS || {}, {
  /** Title card. spec: { eyebrow, title, sub, visual } — keys into the chapter strings. */
  title(root, sc, H) {
    const { el, css, S, rich, vis, eout, at } = H;
    const box = el('div', 'abs', null, root); css(box, { left: 150, top: sc.spec.top ?? 250, width: 1620 });
    const eb = el('div', 'eyebrow', H.esc(S(sc.spec.eyebrow ?? 'eyebrow')), box);
    const h = el('h1', null, rich(S(sc.spec.title ?? 'title')), box); css(h, { fontSize: sc.spec.size ?? 108, marginTop: 34 });
    const sub = el('div', 'lead', rich(S(sc.spec.sub ?? 'sub')), box); css(sub, { marginTop: 34, fontFamily: 'var(--mono)' });
    const extra = sc.spec.visual ? window.VIEWS[sc.spec.visual](root, sc, H) : null;
    return (t) => {
      [[eb, 0.2], [h, 0.45], [sub, at(sc, sc.spec.subAt ?? 1.1)]].forEach(([e, a]) => { const v = vis(t, a, 1e9, 0.6); e.style.opacity = v; e.style.transform = `translateY(${(1 - eout(v)) * 24}px)`; });
      if (extra) extra(t);
    };
  },

  /** Close card: logo, line, links, next chapter. spec: { line, next } */
  close(root, sc, H) {
    const { el, css, S, rich, vis, eout, D } = H;
    const box = el('div', 'abs', null, root); css(box, { left: 0, right: 0, top: 170, textAlign: 'center' });
    const logo = el('div', null, `<svg class="mark" width="150" height="150" viewBox="0 0 128 128">${D.logo}</svg>`, box);
    css(logo, { filter: 'drop-shadow(0 0 40px rgba(246,178,60,.45))' });
    const word = el('h1', null, 'EventConductor', box); css(word, { fontSize: 104, marginTop: 30, letterSpacing: '.02em' });
    const line = el('div', 'lead', rich(S(sc.spec.line ?? 'close.line')), box); css(line, { marginTop: 26, fontFamily: 'var(--mono)', color: 'var(--ink)' });
    const links = el('div', null, null, box); css(links, { marginTop: 50, display: 'flex', gap: '22px', justifyContent: 'center', flexWrap: 'wrap' });
    for (const k of sc.spec.links ?? ['close.docs', 'close.repo', 'close.license']) el('span', 'chip az', rich(S(k)), links);
    const next = el('div', 'eyebrow', H.esc(S(sc.spec.next ?? 'close.next')), box); css(next, { justifyContent: 'center', marginTop: 70 });
    return (t) => { [[logo, 0.1], [word, 0.4], [line, 0.9], [links, 1.5], [next, 2.2]].forEach(([e, a]) => { const v = vis(t, a, 1e9, 0.6); e.style.opacity = v; e.style.transform = `translateY(${(1 - eout(v)) * 20}px)`; }); };
  },

  /** The thumbnail (rendered as a still at 1920x1080, scaled to 1280x720): number + short title. */
  thumb(root, sc, H) {
    const { el, css, S, rich, D } = H;
    const num = el('div', null, String(D.chapter).padStart(2, '0'), root);
    css(num, { position: 'absolute', right: 90, top: 10, fontFamily: 'var(--mono)', fontWeight: 800, fontSize: 520, lineHeight: 1, color: 'transparent', WebkitTextStroke: '4px rgba(246,178,60,.55)', letterSpacing: '-.04em' });
    const logo = el('div', null, `<svg class="mark" width="120" height="120" viewBox="0 0 128 128">${D.logo}</svg>`, root);
    css(logo, { position: 'absolute', left: 120, top: 110, filter: 'drop-shadow(0 0 34px rgba(246,178,60,.5))' });
    const brand = el('div', 'eyebrow', 'EventConductor', root); css(brand, { position: 'absolute', left: 270, top: 150, fontSize: 40 });
    const h = el('h1', null, rich(S('thumb.title')), root); css(h, { position: 'absolute', left: 120, top: 470, width: 1500, fontSize: 132 });
    const sub = el('div', 'lead', rich(S('thumb.sub')), root); css(sub, { position: 'absolute', left: 124, top: 860, fontFamily: 'var(--mono)', fontSize: 46, color: 'var(--azure)' });
    return () => {};
  },
});

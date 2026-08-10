const fe = (t) => (e, i) => {
  i !== void 0 ? i.addInitializer(() => {
    customElements.define(t, e);
  }) : customElements.define(t, e);
};
const N = globalThis, B = N.ShadowRoot && (N.ShadyCSS === void 0 || N.ShadyCSS.nativeShadow) && "adoptedStyleSheets" in Document.prototype && "replace" in CSSStyleSheet.prototype, V = /* @__PURE__ */ Symbol(), Z = /* @__PURE__ */ new WeakMap();
let ae = class {
  constructor(e, i, r) {
    if (this._$cssResult$ = !0, r !== V) throw Error("CSSResult is not constructable. Use `unsafeCSS` or `css` instead.");
    this.cssText = e, this.t = i;
  }
  get styleSheet() {
    let e = this.o;
    const i = this.t;
    if (B && e === void 0) {
      const r = i !== void 0 && i.length === 1;
      r && (e = Z.get(i)), e === void 0 && ((this.o = e = new CSSStyleSheet()).replaceSync(this.cssText), r && Z.set(i, e));
    }
    return e;
  }
  toString() {
    return this.cssText;
  }
};
const ve = (t) => new ae(typeof t == "string" ? t : t + "", void 0, V), le = (t, ...e) => {
  const i = t.length === 1 ? t[0] : e.reduce((r, s, o) => r + ((n) => {
    if (n._$cssResult$ === !0) return n.cssText;
    if (typeof n == "number") return n;
    throw Error("Value passed to 'css' function must be a 'css' function result: " + n + ". Use 'unsafeCSS' to pass non-literal values, but take care to ensure page security.");
  })(s) + t[o + 1], t[0]);
  return new ae(i, t, V);
}, $e = (t, e) => {
  if (B) t.adoptedStyleSheets = e.map((i) => i instanceof CSSStyleSheet ? i : i.styleSheet);
  else for (const i of e) {
    const r = document.createElement("style"), s = N.litNonce;
    s !== void 0 && r.setAttribute("nonce", s), r.textContent = i.cssText, t.appendChild(r);
  }
}, G = B ? (t) => t : (t) => t instanceof CSSStyleSheet ? ((e) => {
  let i = "";
  for (const r of e.cssRules) i += r.cssText;
  return ve(i);
})(t) : t;
const { is: be, defineProperty: ge, getOwnPropertyDescriptor: ye, getOwnPropertyNames: _e, getOwnPropertySymbols: we, getPrototypeOf: xe } = Object, D = globalThis, Q = D.trustedTypes, Ae = Q ? Q.emptyScript : "", Ee = D.reactiveElementPolyfillSupport, S = (t, e) => t, R = { toAttribute(t, e) {
  switch (e) {
    case Boolean:
      t = t ? Ae : null;
      break;
    case Object:
    case Array:
      t = t == null ? t : JSON.stringify(t);
  }
  return t;
}, fromAttribute(t, e) {
  let i = t;
  switch (e) {
    case Boolean:
      i = t !== null;
      break;
    case Number:
      i = t === null ? null : Number(t);
      break;
    case Object:
    case Array:
      try {
        i = JSON.parse(t);
      } catch {
        i = null;
      }
  }
  return i;
} }, W = (t, e) => !be(t, e), X = { attribute: !0, type: String, converter: R, reflect: !1, useDefault: !1, hasChanged: W };
Symbol.metadata ??= /* @__PURE__ */ Symbol("metadata"), D.litPropertyMetadata ??= /* @__PURE__ */ new WeakMap();
let w = class extends HTMLElement {
  static addInitializer(e) {
    this._$Ei(), (this.l ??= []).push(e);
  }
  static get observedAttributes() {
    return this.finalize(), this._$Eh && [...this._$Eh.keys()];
  }
  static createProperty(e, i = X) {
    if (i.state && (i.attribute = !1), this._$Ei(), this.prototype.hasOwnProperty(e) && ((i = Object.create(i)).wrapped = !0), this.elementProperties.set(e, i), !i.noAccessor) {
      const r = /* @__PURE__ */ Symbol(), s = this.getPropertyDescriptor(e, r, i);
      s !== void 0 && ge(this.prototype, e, s);
    }
  }
  static getPropertyDescriptor(e, i, r) {
    const { get: s, set: o } = ye(this.prototype, e) ?? { get() {
      return this[i];
    }, set(n) {
      this[i] = n;
    } };
    return { get: s, set(n) {
      const l = s?.call(this);
      o?.call(this, n), this.requestUpdate(e, l, r);
    }, configurable: !0, enumerable: !0 };
  }
  static getPropertyOptions(e) {
    return this.elementProperties.get(e) ?? X;
  }
  static _$Ei() {
    if (this.hasOwnProperty(S("elementProperties"))) return;
    const e = xe(this);
    e.finalize(), e.l !== void 0 && (this.l = [...e.l]), this.elementProperties = new Map(e.elementProperties);
  }
  static finalize() {
    if (this.hasOwnProperty(S("finalized"))) return;
    if (this.finalized = !0, this._$Ei(), this.hasOwnProperty(S("properties"))) {
      const i = this.properties, r = [..._e(i), ...we(i)];
      for (const s of r) this.createProperty(s, i[s]);
    }
    const e = this[Symbol.metadata];
    if (e !== null) {
      const i = litPropertyMetadata.get(e);
      if (i !== void 0) for (const [r, s] of i) this.elementProperties.set(r, s);
    }
    this._$Eh = /* @__PURE__ */ new Map();
    for (const [i, r] of this.elementProperties) {
      const s = this._$Eu(i, r);
      s !== void 0 && this._$Eh.set(s, i);
    }
    this.elementStyles = this.finalizeStyles(this.styles);
  }
  static finalizeStyles(e) {
    const i = [];
    if (Array.isArray(e)) {
      const r = new Set(e.flat(1 / 0).reverse());
      for (const s of r) i.unshift(G(s));
    } else e !== void 0 && i.push(G(e));
    return i;
  }
  static _$Eu(e, i) {
    const r = i.attribute;
    return r === !1 ? void 0 : typeof r == "string" ? r : typeof e == "string" ? e.toLowerCase() : void 0;
  }
  constructor() {
    super(), this._$Ep = void 0, this.isUpdatePending = !1, this.hasUpdated = !1, this._$Em = null, this._$Ev();
  }
  _$Ev() {
    this._$ES = new Promise((e) => this.enableUpdating = e), this._$AL = /* @__PURE__ */ new Map(), this._$E_(), this.requestUpdate(), this.constructor.l?.forEach((e) => e(this));
  }
  addController(e) {
    (this._$EO ??= /* @__PURE__ */ new Set()).add(e), this.renderRoot !== void 0 && this.isConnected && e.hostConnected?.();
  }
  removeController(e) {
    this._$EO?.delete(e);
  }
  _$E_() {
    const e = /* @__PURE__ */ new Map(), i = this.constructor.elementProperties;
    for (const r of i.keys()) this.hasOwnProperty(r) && (e.set(r, this[r]), delete this[r]);
    e.size > 0 && (this._$Ep = e);
  }
  createRenderRoot() {
    const e = this.shadowRoot ?? this.attachShadow(this.constructor.shadowRootOptions);
    return $e(e, this.constructor.elementStyles), e;
  }
  connectedCallback() {
    this.renderRoot ??= this.createRenderRoot(), this.enableUpdating(!0), this._$EO?.forEach((e) => e.hostConnected?.());
  }
  enableUpdating(e) {
  }
  disconnectedCallback() {
    this._$EO?.forEach((e) => e.hostDisconnected?.());
  }
  attributeChangedCallback(e, i, r) {
    this._$AK(e, r);
  }
  _$ET(e, i) {
    const r = this.constructor.elementProperties.get(e), s = this.constructor._$Eu(e, r);
    if (s !== void 0 && r.reflect === !0) {
      const o = (r.converter?.toAttribute !== void 0 ? r.converter : R).toAttribute(i, r.type);
      this._$Em = e, o == null ? this.removeAttribute(s) : this.setAttribute(s, o), this._$Em = null;
    }
  }
  _$AK(e, i) {
    const r = this.constructor, s = r._$Eh.get(e);
    if (s !== void 0 && this._$Em !== s) {
      const o = r.getPropertyOptions(s), n = typeof o.converter == "function" ? { fromAttribute: o.converter } : o.converter?.fromAttribute !== void 0 ? o.converter : R;
      this._$Em = s;
      const l = n.fromAttribute(i, o.type);
      this[s] = l ?? this._$Ej?.get(s) ?? l, this._$Em = null;
    }
  }
  requestUpdate(e, i, r, s = !1, o) {
    if (e !== void 0) {
      const n = this.constructor;
      if (s === !1 && (o = this[e]), r ??= n.getPropertyOptions(e), !((r.hasChanged ?? W)(o, i) || r.useDefault && r.reflect && o === this._$Ej?.get(e) && !this.hasAttribute(n._$Eu(e, r)))) return;
      this.C(e, i, r);
    }
    this.isUpdatePending === !1 && (this._$ES = this._$EP());
  }
  C(e, i, { useDefault: r, reflect: s, wrapped: o }, n) {
    r && !(this._$Ej ??= /* @__PURE__ */ new Map()).has(e) && (this._$Ej.set(e, n ?? i ?? this[e]), o !== !0 || n !== void 0) || (this._$AL.has(e) || (this.hasUpdated || r || (i = void 0), this._$AL.set(e, i)), s === !0 && this._$Em !== e && (this._$Eq ??= /* @__PURE__ */ new Set()).add(e));
  }
  async _$EP() {
    this.isUpdatePending = !0;
    try {
      await this._$ES;
    } catch (i) {
      Promise.reject(i);
    }
    const e = this.scheduleUpdate();
    return e != null && await e, !this.isUpdatePending;
  }
  scheduleUpdate() {
    return this.performUpdate();
  }
  performUpdate() {
    if (!this.isUpdatePending) return;
    if (!this.hasUpdated) {
      if (this.renderRoot ??= this.createRenderRoot(), this._$Ep) {
        for (const [s, o] of this._$Ep) this[s] = o;
        this._$Ep = void 0;
      }
      const r = this.constructor.elementProperties;
      if (r.size > 0) for (const [s, o] of r) {
        const { wrapped: n } = o, l = this[s];
        n !== !0 || this._$AL.has(s) || l === void 0 || this.C(s, void 0, o, l);
      }
    }
    let e = !1;
    const i = this._$AL;
    try {
      e = this.shouldUpdate(i), e ? (this.willUpdate(i), this._$EO?.forEach((r) => r.hostUpdate?.()), this.update(i)) : this._$EM();
    } catch (r) {
      throw e = !1, this._$EM(), r;
    }
    e && this._$AE(i);
  }
  willUpdate(e) {
  }
  _$AE(e) {
    this._$EO?.forEach((i) => i.hostUpdated?.()), this.hasUpdated || (this.hasUpdated = !0, this.firstUpdated(e)), this.updated(e);
  }
  _$EM() {
    this._$AL = /* @__PURE__ */ new Map(), this.isUpdatePending = !1;
  }
  get updateComplete() {
    return this.getUpdateComplete();
  }
  getUpdateComplete() {
    return this._$ES;
  }
  shouldUpdate(e) {
    return !0;
  }
  update(e) {
    this._$Eq &&= this._$Eq.forEach((i) => this._$ET(i, this[i])), this._$EM();
  }
  updated(e) {
  }
  firstUpdated(e) {
  }
};
w.elementStyles = [], w.shadowRootOptions = { mode: "open" }, w[S("elementProperties")] = /* @__PURE__ */ new Map(), w[S("finalized")] = /* @__PURE__ */ new Map(), Ee?.({ ReactiveElement: w }), (D.reactiveElementVersions ??= []).push("2.1.2");
const Se = { attribute: !0, type: String, converter: R, reflect: !1, hasChanged: W }, Pe = (t = Se, e, i) => {
  const { kind: r, metadata: s } = i;
  let o = globalThis.litPropertyMetadata.get(s);
  if (o === void 0 && globalThis.litPropertyMetadata.set(s, o = /* @__PURE__ */ new Map()), r === "setter" && ((t = Object.create(t)).wrapped = !0), o.set(i.name, t), r === "accessor") {
    const { name: n } = i;
    return { set(l) {
      const a = e.get.call(this);
      e.set.call(this, l), this.requestUpdate(n, a, t, !0, l);
    }, init(l) {
      return l !== void 0 && this.C(n, void 0, t, l), l;
    } };
  }
  if (r === "setter") {
    const { name: n } = i;
    return function(l) {
      const a = this[n];
      e.call(this, l), this.requestUpdate(n, a, t, !0, l);
    };
  }
  throw Error("Unsupported decorator location: " + r);
};
function O(t) {
  return (e, i) => typeof i == "object" ? Pe(t, e, i) : ((r, s, o) => {
    const n = s.hasOwnProperty(o);
    return s.constructor.createProperty(o, r), n ? Object.getOwnPropertyDescriptor(s, o) : void 0;
  })(t, e, i);
}
function j(t) {
  return O({ ...t, state: !0, attribute: !1 });
}
const J = globalThis, ee = (t) => t, z = J.trustedTypes, te = z ? z.createPolicy("lit-html", { createHTML: (t) => t }) : void 0, de = "$lit$", b = `lit$${Math.random().toFixed(9).slice(2)}$`, ce = "?" + b, ke = `<${ce}>`, _ = document, k = () => _.createComment(""), T = (t) => t === null || typeof t != "object" && typeof t != "function", Y = Array.isArray, Te = (t) => Y(t) || typeof t?.[Symbol.iterator] == "function", q = `[ 	
\f\r]`, E = /<(?:(!--|\/[^a-zA-Z])|(\/?[a-zA-Z][^>\s]*)|(\/?$))/g, ie = /-->/g, re = />/g, g = RegExp(`>|${q}(?:([^\\s"'>=/]+)(${q}*=${q}*(?:[^ 	
\f\r"'\`<>=]|("|')|))|$)`, "g"), se = /'/g, oe = /"/g, he = /^(?:script|style|textarea|title)$/i, pe = (t) => (e, ...i) => ({ _$litType$: t, strings: e, values: i }), d = pe(1), U = pe(2), x = /* @__PURE__ */ Symbol.for("lit-noChange"), c = /* @__PURE__ */ Symbol.for("lit-nothing"), ne = /* @__PURE__ */ new WeakMap(), y = _.createTreeWalker(_, 129);
function ue(t, e) {
  if (!Y(t) || !t.hasOwnProperty("raw")) throw Error("invalid template strings array");
  return te !== void 0 ? te.createHTML(e) : e;
}
const Ce = (t, e) => {
  const i = t.length - 1, r = [];
  let s, o = e === 2 ? "<svg>" : e === 3 ? "<math>" : "", n = E;
  for (let l = 0; l < i; l++) {
    const a = t[l];
    let p, u, h = -1, f = 0;
    for (; f < a.length && (n.lastIndex = f, u = n.exec(a), u !== null); ) f = n.lastIndex, n === E ? u[1] === "!--" ? n = ie : u[1] !== void 0 ? n = re : u[2] !== void 0 ? (he.test(u[2]) && (s = RegExp("</" + u[2], "g")), n = g) : u[3] !== void 0 && (n = g) : n === g ? u[0] === ">" ? (n = s ?? E, h = -1) : u[1] === void 0 ? h = -2 : (h = n.lastIndex - u[2].length, p = u[1], n = u[3] === void 0 ? g : u[3] === '"' ? oe : se) : n === oe || n === se ? n = g : n === ie || n === re ? n = E : (n = g, s = void 0);
    const $ = n === g && t[l + 1].startsWith("/>") ? " " : "";
    o += n === E ? a + ke : h >= 0 ? (r.push(p), a.slice(0, h) + de + a.slice(h) + b + $) : a + b + (h === -2 ? l : $);
  }
  return [ue(t, o + (t[i] || "<?>") + (e === 2 ? "</svg>" : e === 3 ? "</math>" : "")), r];
};
class C {
  constructor({ strings: e, _$litType$: i }, r) {
    let s;
    this.parts = [];
    let o = 0, n = 0;
    const l = e.length - 1, a = this.parts, [p, u] = Ce(e, i);
    if (this.el = C.createElement(p, r), y.currentNode = this.el.content, i === 2 || i === 3) {
      const h = this.el.content.firstChild;
      h.replaceWith(...h.childNodes);
    }
    for (; (s = y.nextNode()) !== null && a.length < l; ) {
      if (s.nodeType === 1) {
        if (s.hasAttributes()) for (const h of s.getAttributeNames()) if (h.endsWith(de)) {
          const f = u[n++], $ = s.getAttribute(h).split(b), M = /([.?@])?(.*)/.exec(f);
          a.push({ type: 1, index: o, name: M[2], strings: $, ctor: M[1] === "." ? Ue : M[1] === "?" ? Fe : M[1] === "@" ? Me : L }), s.removeAttribute(h);
        } else h.startsWith(b) && (a.push({ type: 6, index: o }), s.removeAttribute(h));
        if (he.test(s.tagName)) {
          const h = s.textContent.split(b), f = h.length - 1;
          if (f > 0) {
            s.textContent = z ? z.emptyScript : "";
            for (let $ = 0; $ < f; $++) s.append(h[$], k()), y.nextNode(), a.push({ type: 2, index: ++o });
            s.append(h[f], k());
          }
        }
      } else if (s.nodeType === 8) if (s.data === ce) a.push({ type: 2, index: o });
      else {
        let h = -1;
        for (; (h = s.data.indexOf(b, h + 1)) !== -1; ) a.push({ type: 7, index: o }), h += b.length - 1;
      }
      o++;
    }
  }
  static createElement(e, i) {
    const r = _.createElement("template");
    return r.innerHTML = e, r;
  }
}
function A(t, e, i = t, r) {
  if (e === x) return e;
  let s = r !== void 0 ? i._$Co?.[r] : i._$Cl;
  const o = T(e) ? void 0 : e._$litDirective$;
  return s?.constructor !== o && (s?._$AO?.(!1), o === void 0 ? s = void 0 : (s = new o(t), s._$AT(t, i, r)), r !== void 0 ? (i._$Co ??= [])[r] = s : i._$Cl = s), s !== void 0 && (e = A(t, s._$AS(t, e.values), s, r)), e;
}
class Oe {
  constructor(e, i) {
    this._$AV = [], this._$AN = void 0, this._$AD = e, this._$AM = i;
  }
  get parentNode() {
    return this._$AM.parentNode;
  }
  get _$AU() {
    return this._$AM._$AU;
  }
  u(e) {
    const { el: { content: i }, parts: r } = this._$AD, s = (e?.creationScope ?? _).importNode(i, !0);
    y.currentNode = s;
    let o = y.nextNode(), n = 0, l = 0, a = r[0];
    for (; a !== void 0; ) {
      if (n === a.index) {
        let p;
        a.type === 2 ? p = new F(o, o.nextSibling, this, e) : a.type === 1 ? p = new a.ctor(o, a.name, a.strings, this, e) : a.type === 6 && (p = new He(o, this, e)), this._$AV.push(p), a = r[++l];
      }
      n !== a?.index && (o = y.nextNode(), n++);
    }
    return y.currentNode = _, s;
  }
  p(e) {
    let i = 0;
    for (const r of this._$AV) r !== void 0 && (r.strings !== void 0 ? (r._$AI(e, r, i), i += r.strings.length - 2) : r._$AI(e[i])), i++;
  }
}
class F {
  get _$AU() {
    return this._$AM?._$AU ?? this._$Cv;
  }
  constructor(e, i, r, s) {
    this.type = 2, this._$AH = c, this._$AN = void 0, this._$AA = e, this._$AB = i, this._$AM = r, this.options = s, this._$Cv = s?.isConnected ?? !0;
  }
  get parentNode() {
    let e = this._$AA.parentNode;
    const i = this._$AM;
    return i !== void 0 && e?.nodeType === 11 && (e = i.parentNode), e;
  }
  get startNode() {
    return this._$AA;
  }
  get endNode() {
    return this._$AB;
  }
  _$AI(e, i = this) {
    e = A(this, e, i), T(e) ? e === c || e == null || e === "" ? (this._$AH !== c && this._$AR(), this._$AH = c) : e !== this._$AH && e !== x && this._(e) : e._$litType$ !== void 0 ? this.$(e) : e.nodeType !== void 0 ? this.T(e) : Te(e) ? this.k(e) : this._(e);
  }
  O(e) {
    return this._$AA.parentNode.insertBefore(e, this._$AB);
  }
  T(e) {
    this._$AH !== e && (this._$AR(), this._$AH = this.O(e));
  }
  _(e) {
    this._$AH !== c && T(this._$AH) ? this._$AA.nextSibling.data = e : this.T(_.createTextNode(e)), this._$AH = e;
  }
  $(e) {
    const { values: i, _$litType$: r } = e, s = typeof r == "number" ? this._$AC(e) : (r.el === void 0 && (r.el = C.createElement(ue(r.h, r.h[0]), this.options)), r);
    if (this._$AH?._$AD === s) this._$AH.p(i);
    else {
      const o = new Oe(s, this), n = o.u(this.options);
      o.p(i), this.T(n), this._$AH = o;
    }
  }
  _$AC(e) {
    let i = ne.get(e.strings);
    return i === void 0 && ne.set(e.strings, i = new C(e)), i;
  }
  k(e) {
    Y(this._$AH) || (this._$AH = [], this._$AR());
    const i = this._$AH;
    let r, s = 0;
    for (const o of e) s === i.length ? i.push(r = new F(this.O(k()), this.O(k()), this, this.options)) : r = i[s], r._$AI(o), s++;
    s < i.length && (this._$AR(r && r._$AB.nextSibling, s), i.length = s);
  }
  _$AR(e = this._$AA.nextSibling, i) {
    for (this._$AP?.(!1, !0, i); e !== this._$AB; ) {
      const r = ee(e).nextSibling;
      ee(e).remove(), e = r;
    }
  }
  setConnected(e) {
    this._$AM === void 0 && (this._$Cv = e, this._$AP?.(e));
  }
}
class L {
  get tagName() {
    return this.element.tagName;
  }
  get _$AU() {
    return this._$AM._$AU;
  }
  constructor(e, i, r, s, o) {
    this.type = 1, this._$AH = c, this._$AN = void 0, this.element = e, this.name = i, this._$AM = s, this.options = o, r.length > 2 || r[0] !== "" || r[1] !== "" ? (this._$AH = Array(r.length - 1).fill(new String()), this.strings = r) : this._$AH = c;
  }
  _$AI(e, i = this, r, s) {
    const o = this.strings;
    let n = !1;
    if (o === void 0) e = A(this, e, i, 0), n = !T(e) || e !== this._$AH && e !== x, n && (this._$AH = e);
    else {
      const l = e;
      let a, p;
      for (e = o[0], a = 0; a < o.length - 1; a++) p = A(this, l[r + a], i, a), p === x && (p = this._$AH[a]), n ||= !T(p) || p !== this._$AH[a], p === c ? e = c : e !== c && (e += (p ?? "") + o[a + 1]), this._$AH[a] = p;
    }
    n && !s && this.j(e);
  }
  j(e) {
    e === c ? this.element.removeAttribute(this.name) : this.element.setAttribute(this.name, e ?? "");
  }
}
class Ue extends L {
  constructor() {
    super(...arguments), this.type = 3;
  }
  j(e) {
    this.element[this.name] = e === c ? void 0 : e;
  }
}
class Fe extends L {
  constructor() {
    super(...arguments), this.type = 4;
  }
  j(e) {
    this.element.toggleAttribute(this.name, !!e && e !== c);
  }
}
class Me extends L {
  constructor(e, i, r, s, o) {
    super(e, i, r, s, o), this.type = 5;
  }
  _$AI(e, i = this) {
    if ((e = A(this, e, i, 0) ?? c) === x) return;
    const r = this._$AH, s = e === c && r !== c || e.capture !== r.capture || e.once !== r.once || e.passive !== r.passive, o = e !== c && (r === c || s);
    s && this.element.removeEventListener(this.name, this, r), o && this.element.addEventListener(this.name, this, e), this._$AH = e;
  }
  handleEvent(e) {
    typeof this._$AH == "function" ? this._$AH.call(this.options?.host ?? this.element, e) : this._$AH.handleEvent(e);
  }
}
class He {
  constructor(e, i, r) {
    this.element = e, this.type = 6, this._$AN = void 0, this._$AM = i, this.options = r;
  }
  get _$AU() {
    return this._$AM._$AU;
  }
  _$AI(e) {
    A(this, e);
  }
}
const Ne = J.litHtmlPolyfillSupport;
Ne?.(C, F), (J.litHtmlVersions ??= []).push("3.3.3");
const Re = (t, e, i) => {
  const r = i?.renderBefore ?? e;
  let s = r._$litPart$;
  if (s === void 0) {
    const o = i?.renderBefore ?? null;
    r._$litPart$ = s = new F(e.insertBefore(k(), o), o, void 0, i ?? {});
  }
  return s._$AI(t), s;
};
const K = globalThis;
class P extends w {
  constructor() {
    super(...arguments), this.renderOptions = { host: this }, this._$Do = void 0;
  }
  createRenderRoot() {
    const e = super.createRenderRoot();
    return this.renderOptions.renderBefore ??= e.firstChild, e;
  }
  update(e) {
    const i = this.render();
    this.hasUpdated || (this.renderOptions.isConnected = this.isConnected), super.update(e), this._$Do = Re(i, this.renderRoot, this.renderOptions);
  }
  connectedCallback() {
    super.connectedCallback(), this._$Do?.setConnected(!0);
  }
  disconnectedCallback() {
    super.disconnectedCallback(), this._$Do?.setConnected(!1);
  }
  render() {
    return x;
  }
}
P._$litElement$ = !0, P.finalized = !0, K.litElementHydrateSupport?.({ LitElement: P });
const ze = K.litElementPolyfillSupport;
ze?.({ LitElement: P });
(K.litElementVersions ??= []).push("4.2.2");
const De = le`
    .nbtn {
        display: inline-flex;
        align-items: center;
        gap: .35em;
        box-sizing: border-box;
        margin: 0;
        border: none;
        border-radius: var(--lumo-border-radius-m, 4px);
        padding: 0 calc(var(--lumo-space-s, .5rem) + 2px);
        height: var(--lumo-size-s, 1.75rem);
        font-family: inherit;
        font-size: var(--lumo-font-size-s, .875rem);
        font-weight: 500;
        line-height: 1;
        cursor: pointer;
        white-space: nowrap;
        background: transparent;
        color: var(--lumo-primary-text-color, #1676f3);
        transition: background-color .1s;
    }
    .nbtn:hover { background: var(--lumo-primary-color-10pct, rgba(22, 118, 243, .1)); }
    .nbtn:disabled { cursor: default; opacity: .5; background: transparent; }
    .nbtn.primary {
        background: var(--lumo-primary-color, #1676f3);
        color: var(--lumo-primary-contrast-color, #fff);
    }
    .nbtn.primary:hover { background: var(--lumo-primary-color, #1676f3); filter: brightness(1.08); }
    .nbtn svg { width: 1em; height: 1em; flex-shrink: 0; }
`, I = (t) => U`
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
         stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${t}</svg>`, je = I(U`
    <line x1="12" y1="5" x2="12" y2="19"></line>
    <line x1="5" y1="12" x2="19" y2="12"></line>`), Le = I(U`
    <line x1="18" y1="6" x2="6" y2="18"></line>
    <line x1="6" y1="6" x2="18" y2="18"></line>`), Ie = I(U`
    <polyline points="18 15 12 9 6 15"></polyline>`), qe = I(U`
    <polyline points="6 9 12 15 18 9"></polyline>`);
var Be = Object.defineProperty, Ve = Object.getOwnPropertyDescriptor, v = (t, e, i, r) => {
  for (var s = r > 1 ? void 0 : r ? Ve(e, i) : e, o = t.length - 1, n; o >= 0; o--)
    (n = t[o]) && (s = (r ? n(e, i, s) : n(s)) || s);
  return r && s && Be(e, i, s), s;
};
const me = [
  "integer",
  "string",
  "number",
  "date",
  "time",
  "dateTime",
  "bool",
  "array",
  "file",
  "status",
  "money",
  "component",
  "menu",
  "range",
  "action",
  "actionGroup",
  "dateRange"
], We = [
  "regular",
  "radio",
  "checkbox",
  "textarea",
  "toggle",
  "combobox",
  "select",
  "email",
  "password",
  "richText",
  "listBox",
  "html",
  "markdown",
  "image",
  "icon",
  "link",
  "money",
  "grid",
  "color",
  "choice",
  "popover",
  "slider",
  "button",
  "stars"
], H = "regular", Je = { name: "New Form", fields: [] };
function Ye(t) {
  let e = t.length + 1;
  const i = new Set(t.map((r) => r.id));
  for (; i.has("field" + e); ) e++;
  return { id: "field" + e, label: "Field " + e, dataType: "string", stereotype: "regular", required: !1 };
}
let m = class extends P {
  constructor() {
    super(...arguments), this.value = '{"name":"New Form","fields":[]}', this.readOnly = !1, this.noExpand = !1, this.dark = !1, this.form = { name: "New Form", fields: [] }, this.editingId = null, this.showPreview = !0, this.fullscreen = !1;
  }
  // ── Lifecycle ─────────────────────────────────────────────────────────────
  updated(t) {
    if (t.has("value"))
      try {
        const e = JSON.parse(this.value);
        this.form = Ke(e);
      } catch {
      }
  }
  // ── Mutation helpers ──────────────────────────────────────────────────────
  /** Serialise the current form back out in the schema's JSON shape and notify the host. */
  emit() {
    const t = JSON.stringify(Ge(this.form), null, 2);
    this.dispatchEvent(new CustomEvent("value-changed", { detail: { value: t }, bubbles: !0, composed: !0 }));
  }
  updateForm(t) {
    this.form = { ...this.form, ...t }, this.emit();
  }
  updateField(t, e) {
    const i = this.form.fields.map((r, s) => s === t ? { ...r, ...e } : r);
    this.form = { ...this.form, fields: i }, this.emit();
  }
  addField() {
    if (this.readOnly) return;
    const t = Ye(this.form.fields);
    this.form = { ...this.form, fields: [...this.form.fields, t] }, this.editingId = t.id, this.emit();
  }
  removeField(t) {
    if (this.readOnly) return;
    const e = this.form.fields[t];
    this.form = { ...this.form, fields: this.form.fields.filter((i, r) => r !== t) }, this.editingId === e?.id && (this.editingId = null), this.emit();
  }
  moveField(t, e) {
    if (this.readOnly) return;
    const i = t + e;
    if (i < 0 || i >= this.form.fields.length) return;
    const r = [...this.form.fields], [s] = r.splice(t, 1);
    r.splice(i, 0, s), this.form = { ...this.form, fields: r }, this.emit();
  }
  toggleEditing(t) {
    this.editingId = this.editingId === t ? null : t;
  }
  toggleFullscreen() {
    this.fullscreen ? document.fullscreenElement === this && document.exitFullscreen() : this.requestFullscreen?.().catch(() => {
    });
  }
  connectedCallback() {
    super.connectedCallback(), this.fsHandler = () => {
      this.fullscreen = document.fullscreenElement === this;
    }, document.addEventListener("fullscreenchange", this.fsHandler);
  }
  disconnectedCallback() {
    super.disconnectedCallback(), this.fsHandler && document.removeEventListener("fullscreenchange", this.fsHandler);
  }
  // ── Render ────────────────────────────────────────────────────────────────
  render() {
    const t = this.readOnly;
    return d`
            <div class="root ${this.fullscreen ? "fullscreen" : ""}">
                <div class="viewbar">
                    <span class="title">Form editor</span>
                    <span class="spacer"></span>
                    <button class="vbtn" @click="${() => this.showPreview = !this.showPreview}"
                            title="${this.showPreview ? "Hide preview" : "Show preview"}">
                        ${this.showPreview ? "Hide preview" : "Show preview"}
                    </button>
                    ${this.noExpand ? c : d`
                        <button class="vbtn" @click="${() => this.toggleFullscreen()}"
                                title="${this.fullscreen ? "Exit full screen" : "Full screen"}">
                            ${this.fullscreen ? "Exit" : "Expand"}
                        </button>`}
                </div>
                <div class="body ${this.showPreview ? "split" : ""}">
                    <div class="editor">
                        ${this.renderFormMeta(t)}
                        ${this.renderFieldList(t)}
                    </div>
                    ${this.showPreview ? d`<div class="preview">${this.renderPreview()}</div>` : c}
                </div>
            </div>`;
  }
  renderFormMeta(t) {
    return d`
            <div class="section">
                <label class="lbl">Name</label>
                <input class="inp" ?readonly="${t}" .value="${this.form.name ?? ""}"
                       @input="${(e) => this.updateForm({ name: e.target.value })}"/>
                <label class="lbl">Description</label>
                <textarea class="inp" rows="2" ?readonly="${t}" .value="${this.form.description ?? ""}"
                          @input="${(e) => this.updateForm({ description: e.target.value })}"></textarea>
            </div>`;
  }
  renderFieldList(t) {
    return d`
            <div class="section">
                <div class="section-head">
                    <span class="lbl">Fields (${this.form.fields.length})</span>
                    ${t ? c : d`
                        <button class="nbtn primary" @click="${() => this.addField()}">
                            ${je} Add field
                        </button>`}
                </div>
                ${this.form.fields.length === 0 ? d`<div class="empty">No fields yet.${t ? "" : " Use “Add field” to start."}</div>` : this.form.fields.map((e, i) => this.renderFieldRow(e, i, t))}
            </div>`;
  }
  renderFieldRow(t, e, i) {
    const r = this.editingId === t.id;
    return d`
            <div class="field-row ${r ? "open" : ""}">
                <div class="field-head" @click="${() => this.toggleEditing(t.id)}">
                    <span class="field-caret">${r ? "▾" : "▸"}</span>
                    <span class="field-name">${t.label || t.id}</span>
                    <span class="field-meta">${t.dataType}${t.stereotype && t.stereotype !== H ? " · " + t.stereotype : ""}${t.required ? " · required" : ""}</span>
                    <span class="spacer"></span>
                    ${i ? c : d`
                        <button class="icon-btn" title="Move up" ?disabled="${e === 0}"
                                @click="${(s) => {
      s.stopPropagation(), this.moveField(e, -1);
    }}">${Ie}</button>
                        <button class="icon-btn" title="Move down" ?disabled="${e === this.form.fields.length - 1}"
                                @click="${(s) => {
      s.stopPropagation(), this.moveField(e, 1);
    }}">${qe}</button>
                        <button class="icon-btn danger" title="Remove"
                                @click="${(s) => {
      s.stopPropagation(), this.removeField(e);
    }}">${Le}</button>`}
                </div>
                ${r ? this.renderFieldEditor(t, e, i) : c}
            </div>`;
  }
  renderFieldEditor(t, e, i) {
    return d`
            <div class="field-body">
                <div class="grid2">
                    <div>
                        <label class="lbl">ID</label>
                        <input class="inp" ?readonly="${i}" .value="${t.id}"
                               @input="${(r) => this.updateField(e, { id: r.target.value })}"/>
                    </div>
                    <div>
                        <label class="lbl">Label</label>
                        <input class="inp" ?readonly="${i}" .value="${t.label}"
                               @input="${(r) => this.updateField(e, { label: r.target.value })}"/>
                    </div>
                    <div>
                        <label class="lbl">Data type</label>
                        <select class="inp" ?disabled="${i}" .value="${t.dataType}"
                                @change="${(r) => this.updateField(e, { dataType: r.target.value })}">
                            ${me.map((r) => d`<option value="${r}" ?selected="${r === t.dataType}">${r}</option>`)}
                        </select>
                    </div>
                    <div>
                        <label class="lbl">Stereotype</label>
                        <select class="inp" ?disabled="${i}" .value="${t.stereotype ?? H}"
                                @change="${(r) => this.updateField(e, { stereotype: r.target.value })}">
                            ${We.map((r) => d`<option value="${r}" ?selected="${r === (t.stereotype ?? H)}">${r}</option>`)}
                        </select>
                    </div>
                </div>
                <label class="checkline">
                    <input type="checkbox" ?disabled="${i}" .checked="${!!t.required}"
                           @change="${(r) => this.updateField(e, { required: r.target.checked })}"/>
                    Required
                </label>
                <label class="lbl">Description</label>
                <textarea class="inp" rows="2" ?readonly="${i}" .value="${t.description ?? ""}"
                          @input="${(r) => this.updateField(e, { description: r.target.value })}"></textarea>
            </div>`;
  }
  // ── Live preview ───────────────────────────────────────────────────────────
  /** A faithful "what the user will see" render of the form as real (inert) inputs. */
  renderPreview() {
    return d`
            <div class="preview-card">
                <div class="preview-title">${this.form.name || "Untitled form"}</div>
                ${this.form.description ? d`<div class="preview-desc">${this.form.description}</div>` : c}
                ${this.form.fields.length === 0 ? d`<div class="empty">Add fields to see the form preview.</div>` : this.form.fields.map((t) => this.renderPreviewField(t))}
            </div>`;
  }
  renderPreviewField(t) {
    const e = d`<label class="pv-label">${t.label || t.id}${t.required ? d`<span class="pv-req">*</span>` : c}</label>`, i = this.renderPreviewControl(t);
    return d`
            <div class="pv-field">
                ${e}
                ${i}
                ${t.description ? d`<div class="pv-hint">${t.description}</div>` : c}
            </div>`;
  }
  /** Maps dataType + stereotype to the closest real input, so the preview reads like the form. */
  renderPreviewControl(t) {
    const e = t.stereotype ?? H, i = t.label || t.id;
    if (t.dataType === "bool" || e === "checkbox" || e === "toggle")
      return d`<input class="pv-check" type="checkbox" disabled/>`;
    if (e === "textarea" || e === "richText" || e === "html" || e === "markdown" || t.dataType === "component")
      return d`<textarea class="pv-inp" rows="3" disabled placeholder="${i}"></textarea>`;
    if (e === "select" || e === "combobox" || e === "listBox" || e === "choice" || e === "menu" || t.dataType === "status" || t.dataType === "menu")
      return d`<select class="pv-inp" disabled><option>Select…</option></select>`;
    if (e === "radio")
      return d`<div class="pv-radio"><label><input type="radio" disabled/> Option A</label><label><input type="radio" disabled/> Option B</label></div>`;
    if (e === "slider" || e === "range" || t.dataType === "range")
      return d`<input class="pv-inp" type="range" disabled/>`;
    if (e === "color" || t.dataType === "status" && e === "color")
      return d`<input class="pv-inp" type="color" disabled/>`;
    if (e === "button" || t.dataType === "action" || t.dataType === "actionGroup")
      return d`<button class="pv-inp pv-btn" disabled>${i}</button>`;
    if (e === "stars")
      return d`<div class="pv-stars">★★★☆☆</div>`;
    if (t.dataType === "file" || e === "image")
      return d`<input class="pv-inp" type="file" disabled/>`;
    const r = t.dataType === "integer" || t.dataType === "number" || t.dataType === "money" ? "number" : t.dataType === "date" ? "date" : t.dataType === "time" ? "time" : t.dataType === "dateTime" ? "datetime-local" : t.dataType === "dateRange" ? "date" : e === "email" ? "email" : e === "password" ? "password" : e === "link" ? "url" : "text";
    return d`<input class="pv-inp" type="${r}" disabled placeholder="${i}"/>`;
  }
};
m.styles = [De, le`
        :host {
            display: block;
            height: 100%;
            font-family: var(--lumo-font-family, system-ui, sans-serif);
            /* Themeable palette (modux-style). Light defaults; :host([dark]) maps onto Lumo. Kept
               identical to eventconductor-workflow-graph so the two dress alike in either host. */
            --ec-canvas-bg: #f8fafc;
            --ec-surface: #ffffff;
            --ec-border: #e2e8f0;
            --ec-text: #1e293b;
            --ec-text-dim: #64748b;
            --ec-text-faint: #94a3b8;
            --ec-primary: #2563eb;
            --ec-hover: #f1f5f9;
            --ec-danger: #dc2626;
        }
        :host([dark]) {
            --ec-canvas-bg: var(--lumo-shade-5pct, #16181a);
            --ec-surface: var(--lumo-base-color, #1f2123);
            --ec-border: var(--lumo-contrast-20pct, #3a3d42);
            --ec-text: var(--lumo-body-text-color, #e8e9ea);
            --ec-text-dim: var(--lumo-secondary-text-color, #a8adb4);
            --ec-text-faint: var(--lumo-tertiary-text-color, #7d838b);
            --ec-primary: var(--lumo-primary-color, #60a5fa);
            --ec-hover: var(--lumo-contrast-10pct, #2a2e34);
            --ec-danger: var(--lumo-error-color, #f87171);
        }
        .root {
            display: flex; flex-direction: column; height: 100%;
            background: var(--ec-surface); color: var(--ec-text);
            border: 1px solid var(--ec-border); border-radius: 9px; overflow: hidden;
        }
        :host(:fullscreen) { width: 100vw; height: 100vh; }
        :host(:fullscreen) .root { border-radius: 0; border: none; }

        .viewbar {
            display: flex; align-items: center; gap: .5rem;
            padding: .4rem .6rem; border-bottom: 1px solid var(--ec-border);
            background: color-mix(in srgb, var(--ec-surface) 88%, transparent);
        }
        .viewbar .title { font-weight: 600; font-size: .9rem; color: var(--ec-text); }
        .spacer { flex: 1; }
        .vbtn {
            border: none; border-radius: 6px; background: transparent; color: var(--ec-text-dim);
            padding: .25rem .55rem; font: inherit; font-size: .82rem; cursor: pointer;
        }
        .vbtn:hover { background: var(--ec-hover); color: var(--ec-text); }

        .body { flex: 1; min-height: 0; overflow: auto; }
        .body.split { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); }
        .editor { padding: .8rem; min-width: 0; overflow: auto; }
        .preview {
            padding: .8rem; min-width: 0; overflow: auto;
            border-left: 1px solid var(--ec-border); background: var(--ec-canvas-bg);
        }

        .section { margin-bottom: 1rem; }
        .section-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: .4rem; }
        .lbl { display: block; font-size: .72rem; font-weight: 600; text-transform: uppercase;
               letter-spacing: .04em; color: var(--ec-text-dim); margin: .5rem 0 .2rem; }
        .inp {
            box-sizing: border-box; width: 100%; padding: .4rem .5rem;
            border: 1px solid var(--ec-border); border-radius: 6px;
            background: var(--ec-surface); color: var(--ec-text); font: inherit; font-size: .85rem;
        }
        .inp:focus { outline: none; border-color: var(--ec-primary); }
        .inp[readonly], .inp[disabled] { background: var(--ec-hover); color: var(--ec-text-dim); }
        select.inp { appearance: auto; }
        textarea.inp { resize: vertical; }
        .grid2 { display: grid; grid-template-columns: 1fr 1fr; gap: .2rem .6rem; }
        .checkline { display: flex; align-items: center; gap: .4rem; margin: .5rem 0 .2rem;
                     font-size: .85rem; color: var(--ec-text); }
        .empty { padding: .8rem; color: var(--ec-text-faint); font-size: .85rem; font-style: italic; }

        .field-row { border: 1px solid var(--ec-border); border-radius: 7px; margin-bottom: .4rem;
                     background: var(--ec-surface); overflow: hidden; }
        .field-row.open { border-color: var(--ec-primary); }
        .field-head { display: flex; align-items: center; gap: .4rem; padding: .45rem .5rem; cursor: pointer; }
        .field-head:hover { background: var(--ec-hover); }
        .field-caret { color: var(--ec-text-faint); width: 1rem; }
        .field-name { font-weight: 600; font-size: .85rem; color: var(--ec-text); }
        .field-meta { font-size: .72rem; color: var(--ec-text-dim); }
        .icon-btn {
            display: inline-flex; align-items: center; justify-content: center;
            width: 1.5rem; height: 1.5rem; padding: 0; border: none; border-radius: 5px;
            background: transparent; color: var(--ec-text-dim); cursor: pointer;
        }
        .icon-btn svg { width: 1rem; height: 1rem; }
        .icon-btn:hover { background: var(--ec-hover); color: var(--ec-text); }
        .icon-btn.danger:hover { color: var(--ec-danger); }
        .icon-btn:disabled { opacity: .35; cursor: default; background: transparent; }
        .field-body { padding: .2rem .6rem .6rem; border-top: 1px solid var(--ec-border); }

        /* preview */
        .preview-card { max-width: 30rem; }
        .preview-title { font-size: 1.05rem; font-weight: 700; color: var(--ec-text); margin-bottom: .2rem; }
        .preview-desc { font-size: .85rem; color: var(--ec-text-dim); margin-bottom: .8rem; }
        .pv-field { margin-bottom: .8rem; }
        .pv-label { display: block; font-size: .8rem; font-weight: 600; color: var(--ec-text); margin-bottom: .25rem; }
        .pv-req { color: var(--ec-danger); margin-left: .15rem; }
        .pv-inp {
            box-sizing: border-box; width: 100%; padding: .4rem .5rem;
            border: 1px solid var(--ec-border); border-radius: 6px;
            background: var(--ec-surface); color: var(--ec-text); font: inherit; font-size: .85rem;
        }
        .pv-check { width: 1.1rem; height: 1.1rem; }
        .pv-btn { width: auto; cursor: default; background: var(--ec-primary); color: #fff; border: none; }
        .pv-radio { display: flex; gap: 1rem; font-size: .85rem; color: var(--ec-text); }
        .pv-stars { color: #f59e0b; font-size: 1.1rem; letter-spacing: .1rem; }
        .pv-hint { font-size: .75rem; color: var(--ec-text-faint); margin-top: .2rem; }
    `];
v([
  O()
], m.prototype, "value", 2);
v([
  O({ type: Boolean })
], m.prototype, "readOnly", 2);
v([
  O({ type: Boolean, attribute: "no-expand" })
], m.prototype, "noExpand", 2);
v([
  O({ type: Boolean, reflect: !0 })
], m.prototype, "dark", 2);
v([
  j()
], m.prototype, "form", 2);
v([
  j()
], m.prototype, "editingId", 2);
v([
  j()
], m.prototype, "showPreview", 2);
v([
  j()
], m.prototype, "fullscreen", 2);
m = v([
  fe("eventconductor-form-editor")
], m);
function Ke(t) {
  return !t || typeof t != "object" ? { ...Je } : {
    id: t.id ?? void 0,
    name: t.name ?? "New Form",
    description: t.description ?? void 0,
    fields: Array.isArray(t.fields) ? t.fields.map(Ze) : []
  };
}
function Ze(t) {
  return {
    id: t?.id ?? "",
    label: t?.label ?? "",
    dataType: me.includes(t?.dataType) ? t.dataType : "string",
    stereotype: t?.stereotype ?? void 0,
    required: t?.required ?? void 0,
    description: t?.description ?? void 0
  };
}
function Ge(t) {
  const e = { name: t.name ?? "", fields: (t.fields ?? []).map(Qe) };
  return t.id && (e.id = t.id), t.description != null && t.description !== "" && (e.description = t.description), e;
}
function Qe(t) {
  const e = { id: t.id ?? "", label: t.label ?? "", dataType: t.dataType ?? "string" };
  return t.stereotype != null && t.stereotype !== "" && (e.stereotype = t.stereotype), t.required && (e.required = !0), t.description != null && t.description !== "" && (e.description = t.description), e;
}
export {
  m as EventConductorFormEditor
};

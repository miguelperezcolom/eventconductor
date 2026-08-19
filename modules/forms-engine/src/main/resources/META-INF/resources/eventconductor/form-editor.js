const ge = (t) => (e, s) => {
  s !== void 0 ? s.addInitializer(() => {
    customElements.define(t, e);
  }) : customElements.define(t, e);
};
const N = globalThis, j = N.ShadowRoot && (N.ShadyCSS === void 0 || N.ShadyCSS.nativeShadow) && "adoptedStyleSheets" in Document.prototype && "replace" in CSSStyleSheet.prototype, V = /* @__PURE__ */ Symbol(), K = /* @__PURE__ */ new WeakMap();
let he = class {
  constructor(e, s, i) {
    if (this._$cssResult$ = !0, i !== V) throw Error("CSSResult is not constructable. Use `unsafeCSS` or `css` instead.");
    this.cssText = e, this.t = s;
  }
  get styleSheet() {
    let e = this.o;
    const s = this.t;
    if (j && e === void 0) {
      const i = s !== void 0 && s.length === 1;
      i && (e = K.get(s)), e === void 0 && ((this.o = e = new CSSStyleSheet()).replaceSync(this.cssText), i && K.set(s, e));
    }
    return e;
  }
  toString() {
    return this.cssText;
  }
};
const ye = (t) => new he(typeof t == "string" ? t : t + "", void 0, V), pe = (t, ...e) => {
  const s = t.length === 1 ? t[0] : e.reduce((i, r, o) => i + ((n) => {
    if (n._$cssResult$ === !0) return n.cssText;
    if (typeof n == "number") return n;
    throw Error("Value passed to 'css' function must be a 'css' function result: " + n + ". Use 'unsafeCSS' to pass non-literal values, but take care to ensure page security.");
  })(r) + t[o + 1], t[0]);
  return new he(s, t, V);
}, _e = (t, e) => {
  if (j) t.adoptedStyleSheets = e.map((s) => s instanceof CSSStyleSheet ? s : s.styleSheet);
  else for (const s of e) {
    const i = document.createElement("style"), r = N.litNonce;
    r !== void 0 && i.setAttribute("nonce", r), i.textContent = s.cssText, t.appendChild(i);
  }
}, Z = j ? (t) => t : (t) => t instanceof CSSStyleSheet ? ((e) => {
  let s = "";
  for (const i of e.cssRules) s += i.cssText;
  return ye(s);
})(t) : t;
const { is: we, defineProperty: xe, getOwnPropertyDescriptor: Ae, getOwnPropertyNames: Ee, getOwnPropertySymbols: Se, getPrototypeOf: Pe } = Object, D = globalThis, X = D.trustedTypes, ke = X ? X.emptyScript : "", Oe = D.reactiveElementPolyfillSupport, P = (t, e) => t, H = { toAttribute(t, e) {
  switch (e) {
    case Boolean:
      t = t ? ke : null;
      break;
    case Object:
    case Array:
      t = t == null ? t : JSON.stringify(t);
  }
  return t;
}, fromAttribute(t, e) {
  let s = t;
  switch (e) {
    case Boolean:
      s = t !== null;
      break;
    case Number:
      s = t === null ? null : Number(t);
      break;
    case Object:
    case Array:
      try {
        s = JSON.parse(t);
      } catch {
        s = null;
      }
  }
  return s;
} }, W = (t, e) => !we(t, e), Q = { attribute: !0, type: String, converter: H, reflect: !1, useDefault: !1, hasChanged: W };
Symbol.metadata ??= /* @__PURE__ */ Symbol("metadata"), D.litPropertyMetadata ??= /* @__PURE__ */ new WeakMap();
let w = class extends HTMLElement {
  static addInitializer(e) {
    this._$Ei(), (this.l ??= []).push(e);
  }
  static get observedAttributes() {
    return this.finalize(), this._$Eh && [...this._$Eh.keys()];
  }
  static createProperty(e, s = Q) {
    if (s.state && (s.attribute = !1), this._$Ei(), this.prototype.hasOwnProperty(e) && ((s = Object.create(s)).wrapped = !0), this.elementProperties.set(e, s), !s.noAccessor) {
      const i = /* @__PURE__ */ Symbol(), r = this.getPropertyDescriptor(e, i, s);
      r !== void 0 && xe(this.prototype, e, r);
    }
  }
  static getPropertyDescriptor(e, s, i) {
    const { get: r, set: o } = Ae(this.prototype, e) ?? { get() {
      return this[s];
    }, set(n) {
      this[s] = n;
    } };
    return { get: r, set(n) {
      const d = r?.call(this);
      o?.call(this, n), this.requestUpdate(e, d, i);
    }, configurable: !0, enumerable: !0 };
  }
  static getPropertyOptions(e) {
    return this.elementProperties.get(e) ?? Q;
  }
  static _$Ei() {
    if (this.hasOwnProperty(P("elementProperties"))) return;
    const e = Pe(this);
    e.finalize(), e.l !== void 0 && (this.l = [...e.l]), this.elementProperties = new Map(e.elementProperties);
  }
  static finalize() {
    if (this.hasOwnProperty(P("finalized"))) return;
    if (this.finalized = !0, this._$Ei(), this.hasOwnProperty(P("properties"))) {
      const s = this.properties, i = [...Ee(s), ...Se(s)];
      for (const r of i) this.createProperty(r, s[r]);
    }
    const e = this[Symbol.metadata];
    if (e !== null) {
      const s = litPropertyMetadata.get(e);
      if (s !== void 0) for (const [i, r] of s) this.elementProperties.set(i, r);
    }
    this._$Eh = /* @__PURE__ */ new Map();
    for (const [s, i] of this.elementProperties) {
      const r = this._$Eu(s, i);
      r !== void 0 && this._$Eh.set(r, s);
    }
    this.elementStyles = this.finalizeStyles(this.styles);
  }
  static finalizeStyles(e) {
    const s = [];
    if (Array.isArray(e)) {
      const i = new Set(e.flat(1 / 0).reverse());
      for (const r of i) s.unshift(Z(r));
    } else e !== void 0 && s.push(Z(e));
    return s;
  }
  static _$Eu(e, s) {
    const i = s.attribute;
    return i === !1 ? void 0 : typeof i == "string" ? i : typeof e == "string" ? e.toLowerCase() : void 0;
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
    const e = /* @__PURE__ */ new Map(), s = this.constructor.elementProperties;
    for (const i of s.keys()) this.hasOwnProperty(i) && (e.set(i, this[i]), delete this[i]);
    e.size > 0 && (this._$Ep = e);
  }
  createRenderRoot() {
    const e = this.shadowRoot ?? this.attachShadow(this.constructor.shadowRootOptions);
    return _e(e, this.constructor.elementStyles), e;
  }
  connectedCallback() {
    this.renderRoot ??= this.createRenderRoot(), this.enableUpdating(!0), this._$EO?.forEach((e) => e.hostConnected?.());
  }
  enableUpdating(e) {
  }
  disconnectedCallback() {
    this._$EO?.forEach((e) => e.hostDisconnected?.());
  }
  attributeChangedCallback(e, s, i) {
    this._$AK(e, i);
  }
  _$ET(e, s) {
    const i = this.constructor.elementProperties.get(e), r = this.constructor._$Eu(e, i);
    if (r !== void 0 && i.reflect === !0) {
      const o = (i.converter?.toAttribute !== void 0 ? i.converter : H).toAttribute(s, i.type);
      this._$Em = e, o == null ? this.removeAttribute(r) : this.setAttribute(r, o), this._$Em = null;
    }
  }
  _$AK(e, s) {
    const i = this.constructor, r = i._$Eh.get(e);
    if (r !== void 0 && this._$Em !== r) {
      const o = i.getPropertyOptions(r), n = typeof o.converter == "function" ? { fromAttribute: o.converter } : o.converter?.fromAttribute !== void 0 ? o.converter : H;
      this._$Em = r;
      const d = n.fromAttribute(s, o.type);
      this[r] = d ?? this._$Ej?.get(r) ?? d, this._$Em = null;
    }
  }
  requestUpdate(e, s, i, r = !1, o) {
    if (e !== void 0) {
      const n = this.constructor;
      if (r === !1 && (o = this[e]), i ??= n.getPropertyOptions(e), !((i.hasChanged ?? W)(o, s) || i.useDefault && i.reflect && o === this._$Ej?.get(e) && !this.hasAttribute(n._$Eu(e, i)))) return;
      this.C(e, s, i);
    }
    this.isUpdatePending === !1 && (this._$ES = this._$EP());
  }
  C(e, s, { useDefault: i, reflect: r, wrapped: o }, n) {
    i && !(this._$Ej ??= /* @__PURE__ */ new Map()).has(e) && (this._$Ej.set(e, n ?? s ?? this[e]), o !== !0 || n !== void 0) || (this._$AL.has(e) || (this.hasUpdated || i || (s = void 0), this._$AL.set(e, s)), r === !0 && this._$Em !== e && (this._$Eq ??= /* @__PURE__ */ new Set()).add(e));
  }
  async _$EP() {
    this.isUpdatePending = !0;
    try {
      await this._$ES;
    } catch (s) {
      Promise.reject(s);
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
        for (const [r, o] of this._$Ep) this[r] = o;
        this._$Ep = void 0;
      }
      const i = this.constructor.elementProperties;
      if (i.size > 0) for (const [r, o] of i) {
        const { wrapped: n } = o, d = this[r];
        n !== !0 || this._$AL.has(r) || d === void 0 || this.C(r, void 0, o, d);
      }
    }
    let e = !1;
    const s = this._$AL;
    try {
      e = this.shouldUpdate(s), e ? (this.willUpdate(s), this._$EO?.forEach((i) => i.hostUpdate?.()), this.update(s)) : this._$EM();
    } catch (i) {
      throw e = !1, this._$EM(), i;
    }
    e && this._$AE(s);
  }
  willUpdate(e) {
  }
  _$AE(e) {
    this._$EO?.forEach((s) => s.hostUpdated?.()), this.hasUpdated || (this.hasUpdated = !0, this.firstUpdated(e)), this.updated(e);
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
    this._$Eq &&= this._$Eq.forEach((s) => this._$ET(s, this[s])), this._$EM();
  }
  updated(e) {
  }
  firstUpdated(e) {
  }
};
w.elementStyles = [], w.shadowRootOptions = { mode: "open" }, w[P("elementProperties")] = /* @__PURE__ */ new Map(), w[P("finalized")] = /* @__PURE__ */ new Map(), Oe?.({ ReactiveElement: w }), (D.reactiveElementVersions ??= []).push("2.1.2");
const Te = { attribute: !0, type: String, converter: H, reflect: !1, hasChanged: W }, Ce = (t = Te, e, s) => {
  const { kind: i, metadata: r } = s;
  let o = globalThis.litPropertyMetadata.get(r);
  if (o === void 0 && globalThis.litPropertyMetadata.set(r, o = /* @__PURE__ */ new Map()), i === "setter" && ((t = Object.create(t)).wrapped = !0), o.set(s.name, t), i === "accessor") {
    const { name: n } = s;
    return { set(d) {
      const l = e.get.call(this);
      e.set.call(this, d), this.requestUpdate(n, l, t, !0, d);
    }, init(d) {
      return d !== void 0 && this.C(n, void 0, t, d), d;
    } };
  }
  if (i === "setter") {
    const { name: n } = s;
    return function(d) {
      const l = this[n];
      e.call(this, d), this.requestUpdate(n, l, t, !0, d);
    };
  }
  throw Error("Unsupported decorator location: " + i);
};
function F(t) {
  return (e, s) => typeof s == "object" ? Ce(t, e, s) : ((i, r, o) => {
    const n = r.hasOwnProperty(o);
    return r.constructor.createProperty(o, i), n ? Object.getOwnPropertyDescriptor(r, o) : void 0;
  })(t, e, s);
}
function L(t) {
  return F({ ...t, state: !0, attribute: !1 });
}
const Y = globalThis, ee = (t) => t, z = Y.trustedTypes, te = z ? z.createPolicy("lit-html", { createHTML: (t) => t }) : void 0, ue = "$lit$", $ = `lit$${Math.random().toFixed(9).slice(2)}$`, me = "?" + $, Fe = `<${me}>`, _ = document, O = () => _.createComment(""), T = (t) => t === null || typeof t != "object" && typeof t != "function", J = Array.isArray, Ue = (t) => J(t) || typeof t?.[Symbol.iterator] == "function", B = `[ 	
\f\r]`, E = /<(?:(!--|\/[^a-zA-Z])|(\/?[a-zA-Z][^>\s]*)|(\/?$))/g, ie = /-->/g, se = />/g, g = RegExp(`>|${B}(?:([^\\s"'>=/]+)(${B}*=${B}*(?:[^ 	
\f\r"'\`<>=]|("|')|))|$)`, "g"), re = /'/g, oe = /"/g, ve = /^(?:script|style|textarea|title)$/i, fe = (t) => (e, ...s) => ({ _$litType$: t, strings: e, values: s }), a = fe(1), U = fe(2), x = /* @__PURE__ */ Symbol.for("lit-noChange"), c = /* @__PURE__ */ Symbol.for("lit-nothing"), ne = /* @__PURE__ */ new WeakMap(), y = _.createTreeWalker(_, 129);
function be(t, e) {
  if (!J(t) || !t.hasOwnProperty("raw")) throw Error("invalid template strings array");
  return te !== void 0 ? te.createHTML(e) : e;
}
const Me = (t, e) => {
  const s = t.length - 1, i = [];
  let r, o = e === 2 ? "<svg>" : e === 3 ? "<math>" : "", n = E;
  for (let d = 0; d < s; d++) {
    const l = t[d];
    let p, u, h = -1, v = 0;
    for (; v < l.length && (n.lastIndex = v, u = n.exec(l), u !== null); ) v = n.lastIndex, n === E ? u[1] === "!--" ? n = ie : u[1] !== void 0 ? n = se : u[2] !== void 0 ? (ve.test(u[2]) && (r = RegExp("</" + u[2], "g")), n = g) : u[3] !== void 0 && (n = g) : n === g ? u[0] === ">" ? (n = r ?? E, h = -1) : u[1] === void 0 ? h = -2 : (h = n.lastIndex - u[2].length, p = u[1], n = u[3] === void 0 ? g : u[3] === '"' ? oe : re) : n === oe || n === re ? n = g : n === ie || n === se ? n = E : (n = g, r = void 0);
    const b = n === g && t[d + 1].startsWith("/>") ? " " : "";
    o += n === E ? l + Fe : h >= 0 ? (i.push(p), l.slice(0, h) + ue + l.slice(h) + $ + b) : l + $ + (h === -2 ? d : b);
  }
  return [be(t, o + (t[s] || "<?>") + (e === 2 ? "</svg>" : e === 3 ? "</math>" : "")), i];
};
class C {
  constructor({ strings: e, _$litType$: s }, i) {
    let r;
    this.parts = [];
    let o = 0, n = 0;
    const d = e.length - 1, l = this.parts, [p, u] = Me(e, s);
    if (this.el = C.createElement(p, i), y.currentNode = this.el.content, s === 2 || s === 3) {
      const h = this.el.content.firstChild;
      h.replaceWith(...h.childNodes);
    }
    for (; (r = y.nextNode()) !== null && l.length < d; ) {
      if (r.nodeType === 1) {
        if (r.hasAttributes()) for (const h of r.getAttributeNames()) if (h.endsWith(ue)) {
          const v = u[n++], b = r.getAttribute(h).split($), R = /([.?@])?(.*)/.exec(v);
          l.push({ type: 1, index: o, name: R[2], strings: b, ctor: R[1] === "." ? Ne : R[1] === "?" ? He : R[1] === "@" ? ze : I }), r.removeAttribute(h);
        } else h.startsWith($) && (l.push({ type: 6, index: o }), r.removeAttribute(h));
        if (ve.test(r.tagName)) {
          const h = r.textContent.split($), v = h.length - 1;
          if (v > 0) {
            r.textContent = z ? z.emptyScript : "";
            for (let b = 0; b < v; b++) r.append(h[b], O()), y.nextNode(), l.push({ type: 2, index: ++o });
            r.append(h[v], O());
          }
        }
      } else if (r.nodeType === 8) if (r.data === me) l.push({ type: 2, index: o });
      else {
        let h = -1;
        for (; (h = r.data.indexOf($, h + 1)) !== -1; ) l.push({ type: 7, index: o }), h += $.length - 1;
      }
      o++;
    }
  }
  static createElement(e, s) {
    const i = _.createElement("template");
    return i.innerHTML = e, i;
  }
}
function A(t, e, s = t, i) {
  if (e === x) return e;
  let r = i !== void 0 ? s._$Co?.[i] : s._$Cl;
  const o = T(e) ? void 0 : e._$litDirective$;
  return r?.constructor !== o && (r?._$AO?.(!1), o === void 0 ? r = void 0 : (r = new o(t), r._$AT(t, s, i)), i !== void 0 ? (s._$Co ??= [])[i] = r : s._$Cl = r), r !== void 0 && (e = A(t, r._$AS(t, e.values), r, i)), e;
}
class Re {
  constructor(e, s) {
    this._$AV = [], this._$AN = void 0, this._$AD = e, this._$AM = s;
  }
  get parentNode() {
    return this._$AM.parentNode;
  }
  get _$AU() {
    return this._$AM._$AU;
  }
  u(e) {
    const { el: { content: s }, parts: i } = this._$AD, r = (e?.creationScope ?? _).importNode(s, !0);
    y.currentNode = r;
    let o = y.nextNode(), n = 0, d = 0, l = i[0];
    for (; l !== void 0; ) {
      if (n === l.index) {
        let p;
        l.type === 2 ? p = new M(o, o.nextSibling, this, e) : l.type === 1 ? p = new l.ctor(o, l.name, l.strings, this, e) : l.type === 6 && (p = new De(o, this, e)), this._$AV.push(p), l = i[++d];
      }
      n !== l?.index && (o = y.nextNode(), n++);
    }
    return y.currentNode = _, r;
  }
  p(e) {
    let s = 0;
    for (const i of this._$AV) i !== void 0 && (i.strings !== void 0 ? (i._$AI(e, i, s), s += i.strings.length - 2) : i._$AI(e[s])), s++;
  }
}
class M {
  get _$AU() {
    return this._$AM?._$AU ?? this._$Cv;
  }
  constructor(e, s, i, r) {
    this.type = 2, this._$AH = c, this._$AN = void 0, this._$AA = e, this._$AB = s, this._$AM = i, this.options = r, this._$Cv = r?.isConnected ?? !0;
  }
  get parentNode() {
    let e = this._$AA.parentNode;
    const s = this._$AM;
    return s !== void 0 && e?.nodeType === 11 && (e = s.parentNode), e;
  }
  get startNode() {
    return this._$AA;
  }
  get endNode() {
    return this._$AB;
  }
  _$AI(e, s = this) {
    e = A(this, e, s), T(e) ? e === c || e == null || e === "" ? (this._$AH !== c && this._$AR(), this._$AH = c) : e !== this._$AH && e !== x && this._(e) : e._$litType$ !== void 0 ? this.$(e) : e.nodeType !== void 0 ? this.T(e) : Ue(e) ? this.k(e) : this._(e);
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
    const { values: s, _$litType$: i } = e, r = typeof i == "number" ? this._$AC(e) : (i.el === void 0 && (i.el = C.createElement(be(i.h, i.h[0]), this.options)), i);
    if (this._$AH?._$AD === r) this._$AH.p(s);
    else {
      const o = new Re(r, this), n = o.u(this.options);
      o.p(s), this.T(n), this._$AH = o;
    }
  }
  _$AC(e) {
    let s = ne.get(e.strings);
    return s === void 0 && ne.set(e.strings, s = new C(e)), s;
  }
  k(e) {
    J(this._$AH) || (this._$AH = [], this._$AR());
    const s = this._$AH;
    let i, r = 0;
    for (const o of e) r === s.length ? s.push(i = new M(this.O(O()), this.O(O()), this, this.options)) : i = s[r], i._$AI(o), r++;
    r < s.length && (this._$AR(i && i._$AB.nextSibling, r), s.length = r);
  }
  _$AR(e = this._$AA.nextSibling, s) {
    for (this._$AP?.(!1, !0, s); e !== this._$AB; ) {
      const i = ee(e).nextSibling;
      ee(e).remove(), e = i;
    }
  }
  setConnected(e) {
    this._$AM === void 0 && (this._$Cv = e, this._$AP?.(e));
  }
}
class I {
  get tagName() {
    return this.element.tagName;
  }
  get _$AU() {
    return this._$AM._$AU;
  }
  constructor(e, s, i, r, o) {
    this.type = 1, this._$AH = c, this._$AN = void 0, this.element = e, this.name = s, this._$AM = r, this.options = o, i.length > 2 || i[0] !== "" || i[1] !== "" ? (this._$AH = Array(i.length - 1).fill(new String()), this.strings = i) : this._$AH = c;
  }
  _$AI(e, s = this, i, r) {
    const o = this.strings;
    let n = !1;
    if (o === void 0) e = A(this, e, s, 0), n = !T(e) || e !== this._$AH && e !== x, n && (this._$AH = e);
    else {
      const d = e;
      let l, p;
      for (e = o[0], l = 0; l < o.length - 1; l++) p = A(this, d[i + l], s, l), p === x && (p = this._$AH[l]), n ||= !T(p) || p !== this._$AH[l], p === c ? e = c : e !== c && (e += (p ?? "") + o[l + 1]), this._$AH[l] = p;
    }
    n && !r && this.j(e);
  }
  j(e) {
    e === c ? this.element.removeAttribute(this.name) : this.element.setAttribute(this.name, e ?? "");
  }
}
class Ne extends I {
  constructor() {
    super(...arguments), this.type = 3;
  }
  j(e) {
    this.element[this.name] = e === c ? void 0 : e;
  }
}
class He extends I {
  constructor() {
    super(...arguments), this.type = 4;
  }
  j(e) {
    this.element.toggleAttribute(this.name, !!e && e !== c);
  }
}
class ze extends I {
  constructor(e, s, i, r, o) {
    super(e, s, i, r, o), this.type = 5;
  }
  _$AI(e, s = this) {
    if ((e = A(this, e, s, 0) ?? c) === x) return;
    const i = this._$AH, r = e === c && i !== c || e.capture !== i.capture || e.once !== i.once || e.passive !== i.passive, o = e !== c && (i === c || r);
    r && this.element.removeEventListener(this.name, this, i), o && this.element.addEventListener(this.name, this, e), this._$AH = e;
  }
  handleEvent(e) {
    typeof this._$AH == "function" ? this._$AH.call(this.options?.host ?? this.element, e) : this._$AH.handleEvent(e);
  }
}
class De {
  constructor(e, s, i) {
    this.element = e, this.type = 6, this._$AN = void 0, this._$AM = s, this.options = i;
  }
  get _$AU() {
    return this._$AM._$AU;
  }
  _$AI(e) {
    A(this, e);
  }
}
const Le = Y.litHtmlPolyfillSupport;
Le?.(C, M), (Y.litHtmlVersions ??= []).push("3.3.3");
const Ie = (t, e, s) => {
  const i = s?.renderBefore ?? e;
  let r = i._$litPart$;
  if (r === void 0) {
    const o = s?.renderBefore ?? null;
    i._$litPart$ = r = new M(e.insertBefore(O(), o), o, void 0, s ?? {});
  }
  return r._$AI(t), r;
};
const G = globalThis;
class k extends w {
  constructor() {
    super(...arguments), this.renderOptions = { host: this }, this._$Do = void 0;
  }
  createRenderRoot() {
    const e = super.createRenderRoot();
    return this.renderOptions.renderBefore ??= e.firstChild, e;
  }
  update(e) {
    const s = this.render();
    this.hasUpdated || (this.renderOptions.isConnected = this.isConnected), super.update(e), this._$Do = Ie(s, this.renderRoot, this.renderOptions);
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
k._$litElement$ = !0, k.finalized = !0, G.litElementHydrateSupport?.({ LitElement: k });
const qe = G.litElementPolyfillSupport;
qe?.({ LitElement: k });
(G.litElementVersions ??= []).push("4.2.2");
const Be = pe`
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
`, q = (t) => U`
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
         stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${t}</svg>`, ae = q(U`
    <line x1="12" y1="5" x2="12" y2="19"></line>
    <line x1="5" y1="12" x2="19" y2="12"></line>`), le = q(U`
    <line x1="18" y1="6" x2="6" y2="18"></line>
    <line x1="6" y1="6" x2="18" y2="18"></line>`), de = q(U`
    <polyline points="18 15 12 9 6 15"></polyline>`), ce = q(U`
    <polyline points="6 9 12 15 18 9"></polyline>`);
var je = Object.defineProperty, Ve = Object.getOwnPropertyDescriptor, f = (t, e, s, i) => {
  for (var r = i > 1 ? void 0 : i ? Ve(e, s) : e, o = t.length - 1, n; o >= 0; o--)
    (n = t[o]) && (r = (i ? n(e, s, r) : n(r)) || r);
  return i && r && je(e, s, r), r;
};
const $e = [
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
], S = "regular", Ye = ["radio", "select", "combobox", "listBox", "choice"], Je = { name: "New Form", fields: [] };
function Ge(t) {
  let e = t.length + 1;
  const s = new Set(t.map((i) => i.id));
  for (; s.has("field" + e); ) e++;
  return { id: "field" + e, label: "Field " + e, dataType: "string", stereotype: "regular", required: !1 };
}
let m = class extends k {
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
    const t = JSON.stringify(Xe(this.form), null, 2);
    this.dispatchEvent(new CustomEvent("value-changed", { detail: { value: t }, bubbles: !0, composed: !0 }));
  }
  updateForm(t) {
    this.form = { ...this.form, ...t }, this.emit();
  }
  updateField(t, e) {
    const s = this.form.fields.map((i, r) => r === t ? { ...i, ...e } : i);
    this.form = { ...this.form, fields: s }, this.emit();
  }
  /** Every option edit goes through here, so one path patches the list and emits. */
  updateOptions(t, e) {
    if (this.readOnly) return;
    const s = this.form.fields[t];
    this.updateField(t, { options: e([...s.options ?? []]) });
  }
  addOption(t) {
    this.updateOptions(t, (e) => [...e, { value: "" }]);
  }
  updateOption(t, e, s) {
    this.updateOptions(t, (i) => i.map((r, o) => o === e ? { ...r, ...s } : r));
  }
  removeOption(t, e) {
    this.updateOptions(t, (s) => s.filter((i, r) => r !== e));
  }
  moveOption(t, e, s) {
    this.updateOptions(t, (i) => {
      const r = e + s;
      if (r < 0 || r >= i.length) return i;
      const [o] = i.splice(e, 1);
      return i.splice(r, 0, o), i;
    });
  }
  addField() {
    if (this.readOnly) return;
    const t = Ge(this.form.fields);
    this.form = { ...this.form, fields: [...this.form.fields, t] }, this.editingId = t.id, this.emit();
  }
  removeField(t) {
    if (this.readOnly) return;
    const e = this.form.fields[t];
    this.form = { ...this.form, fields: this.form.fields.filter((s, i) => i !== t) }, this.editingId === e?.id && (this.editingId = null), this.emit();
  }
  moveField(t, e) {
    if (this.readOnly) return;
    const s = t + e;
    if (s < 0 || s >= this.form.fields.length) return;
    const i = [...this.form.fields], [r] = i.splice(t, 1);
    i.splice(s, 0, r), this.form = { ...this.form, fields: i }, this.emit();
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
    return a`
            <div class="root ${this.fullscreen ? "fullscreen" : ""}">
                <div class="viewbar">
                    <span class="title">Form editor</span>
                    <span class="spacer"></span>
                    <button class="vbtn" @click="${() => this.showPreview = !this.showPreview}"
                            title="${this.showPreview ? "Hide preview" : "Show preview"}">
                        ${this.showPreview ? "Hide preview" : "Show preview"}
                    </button>
                    ${this.noExpand ? c : a`
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
                    ${this.showPreview ? a`<div class="preview">${this.renderPreview()}</div>` : c}
                </div>
            </div>`;
  }
  renderFormMeta(t) {
    return a`
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
    return a`
            <div class="section">
                <div class="section-head">
                    <span class="lbl">Fields (${this.form.fields.length})</span>
                    ${t ? c : a`
                        <button class="nbtn primary" @click="${() => this.addField()}">
                            ${ae} Add field
                        </button>`}
                </div>
                ${this.form.fields.length === 0 ? a`<div class="empty">No fields yet.${t ? "" : " Use “Add field” to start."}</div>` : this.form.fields.map((e, s) => this.renderFieldRow(e, s, t))}
            </div>`;
  }
  renderFieldRow(t, e, s) {
    const i = this.editingId === t.id;
    return a`
            <div class="field-row ${i ? "open" : ""}">
                <div class="field-head" @click="${() => this.toggleEditing(t.id)}">
                    <span class="field-caret">${i ? "▾" : "▸"}</span>
                    <span class="field-name">${t.label || t.id}</span>
                    <span class="field-meta">${t.dataType}${t.stereotype && t.stereotype !== S ? " · " + t.stereotype : ""}${t.required ? " · required" : ""}</span>
                    <span class="spacer"></span>
                    ${s ? c : a`
                        <button class="icon-btn" title="Move up" ?disabled="${e === 0}"
                                @click="${(r) => {
      r.stopPropagation(), this.moveField(e, -1);
    }}">${de}</button>
                        <button class="icon-btn" title="Move down" ?disabled="${e === this.form.fields.length - 1}"
                                @click="${(r) => {
      r.stopPropagation(), this.moveField(e, 1);
    }}">${ce}</button>
                        <button class="icon-btn danger" title="Remove"
                                @click="${(r) => {
      r.stopPropagation(), this.removeField(e);
    }}">${le}</button>`}
                </div>
                ${i ? this.renderFieldEditor(t, e, s) : c}
            </div>`;
  }
  renderFieldEditor(t, e, s) {
    return a`
            <div class="field-body">
                <div class="grid2">
                    <div>
                        <label class="lbl">ID</label>
                        <input class="inp" ?readonly="${s}" .value="${t.id}"
                               @input="${(i) => this.updateField(e, { id: i.target.value })}"/>
                    </div>
                    <div>
                        <label class="lbl">Label</label>
                        <input class="inp" ?readonly="${s}" .value="${t.label}"
                               @input="${(i) => this.updateField(e, { label: i.target.value })}"/>
                    </div>
                    <div>
                        <label class="lbl">Data type</label>
                        <select class="inp" ?disabled="${s}" .value="${t.dataType}"
                                @change="${(i) => this.updateField(e, { dataType: i.target.value })}">
                            ${$e.map((i) => a`<option value="${i}" ?selected="${i === t.dataType}">${i}</option>`)}
                        </select>
                    </div>
                    <div>
                        <label class="lbl">Stereotype</label>
                        <select class="inp" ?disabled="${s}" .value="${t.stereotype ?? S}"
                                @change="${(i) => this.updateField(e, { stereotype: i.target.value })}">
                            ${We.map((i) => a`<option value="${i}" ?selected="${i === (t.stereotype ?? S)}">${i}</option>`)}
                        </select>
                    </div>
                </div>
                <label class="checkline">
                    <input type="checkbox" ?disabled="${s}" .checked="${!!t.required}"
                           @change="${(i) => this.updateField(e, { required: i.target.checked })}"/>
                    Required
                </label>
                <label class="lbl">Description</label>
                <textarea class="inp" rows="2" ?readonly="${s}" .value="${t.description ?? ""}"
                          @input="${(i) => this.updateField(e, { description: i.target.value })}"></textarea>
                ${Ye.includes(t.stereotype ?? S) ? this.renderChoices(t, e, s) : c}
            </div>`;
  }
  /**
   * The choices a picking field offers. Shown only for the stereotypes that take them, so the
   * panel says what the field actually has: switching a field to "radio" is what reveals it.
   */
  renderChoices(t, e, s) {
    const i = t.options ?? [], r = !!t.optionsSource;
    return a`
            <div class="choices">
                <div class="section-head">
                    <span class="lbl">Choices</span>
                    <select class="inp mode" ?disabled="${s}" .value="${r ? "rest" : "fixed"}"
                            @change="${(o) => this.setChoicesMode(e, o.target.value)}">
                        <option value="fixed" ?selected="${!r}">listed here</option>
                        <option value="rest" ?selected="${r}">from a REST endpoint</option>
                    </select>
                </div>
                ${r ? this.renderOptionsSource(t.optionsSource, e, s) : this.renderFixedChoices(i, e, s)}
            </div>`;
  }
  /** The endpoint descriptor. A field declares this or its own list, never both. */
  renderOptionsSource(t, e, s) {
    const i = (r) => this.updateField(e, { optionsSource: { ...t, ...r }, options: void 0 });
    return a`
            <label class="lbl">URL</label>
            <input class="inp" placeholder="https://api.example.com/countries" ?readonly="${s}"
                   .value="${t.url ?? ""}"
                   @input="${(r) => i({ url: r.target.value })}"/>
            <div class="grid2">
                <div>
                    <label class="lbl">Items path</label>
                    <input class="inp" placeholder="(response root)" ?readonly="${s}"
                           .value="${t.itemsPath ?? ""}"
                           @input="${(r) => i({ itemsPath: r.target.value })}"/>
                </div>
                <div>
                    <label class="lbl">Method</label>
                    <input class="inp" placeholder="GET" ?readonly="${s}" .value="${t.method ?? ""}"
                           @input="${(r) => i({ method: r.target.value })}"/>
                </div>
                <div>
                    <label class="lbl">Value path</label>
                    <input class="inp" placeholder="value" ?readonly="${s}" .value="${t.valuePath ?? ""}"
                           @input="${(r) => i({ valuePath: r.target.value })}"/>
                </div>
                <div>
                    <label class="lbl">Label path</label>
                    <input class="inp" placeholder="label" ?readonly="${s}" .value="${t.labelPath ?? ""}"
                           @input="${(r) => i({ labelPath: r.target.value })}"/>
                </div>
            </div>
            <label class="checkline">
                <input type="checkbox" ?disabled="${s}" .checked="${!!t.proxy}"
                       @change="${(r) => i({ proxy: r.target.checked })}"/>
                Fetch through the server (no CORS, secrets stay server-side)
            </label>
            <div class="hint">${t.proxy ? "The server calls the endpoint. A ${secret.X} placeholder in the url or a header is resolved there and never reaches the browser." : "The browser calls the endpoint: it must be reachable from there and allow CORS, and a header written here is one the browser can read."}</div>`;
  }
  renderFixedChoices(t, e, s) {
    return a`
            <div class="section-head">
                <span class="lbl">${t.length} listed</span>
                ${s ? c : a`
                    <button class="nbtn" @click="${() => this.addOption(e)}">${ae} Add choice</button>`}
            </div>
                ${t.length === 0 ? a`<div class="empty">No choices yet.${s ? "" : " The field will render empty."}</div>` : t.map((i, r) => a`
                        <div class="choice-row">
                            <input class="inp" placeholder="value" ?readonly="${s}" .value="${i.value ?? ""}"
                                   @input="${(o) => this.updateOption(e, r, { value: o.target.value })}"/>
                            <input class="inp" placeholder="${i.value || "label"}" ?readonly="${s}"
                                   .value="${i.label ?? ""}"
                                   @input="${(o) => this.updateOption(e, r, { label: o.target.value })}"/>
                            ${s ? c : a`
                                <button class="icon-btn" title="Move up" ?disabled="${r === 0}"
                                        @click="${() => this.moveOption(e, r, -1)}">${de}</button>
                                <button class="icon-btn" title="Move down" ?disabled="${r === t.length - 1}"
                                        @click="${() => this.moveOption(e, r, 1)}">${ce}</button>
                                <button class="icon-btn danger" title="Remove"
                                        @click="${() => this.removeOption(e, r)}">${le}</button>`}
                        </div>`)}`;
  }
  /** Switching mode drops the other side, so the saved field only ever carries one of the two. */
  setChoicesMode(t, e) {
    this.readOnly || this.updateField(t, e === "rest" ? { optionsSource: { url: "", valuePath: "value", labelPath: "label" }, options: void 0 } : { optionsSource: void 0, options: [] });
  }
  // ── Live preview ───────────────────────────────────────────────────────────
  /** A faithful "what the user will see" render of the form as real (inert) inputs. */
  renderPreview() {
    return a`
            <div class="preview-card">
                <div class="preview-title">${this.form.name || "Untitled form"}</div>
                ${this.form.description ? a`<div class="preview-desc">${this.form.description}</div>` : c}
                ${this.form.fields.length === 0 ? a`<div class="empty">Add fields to see the form preview.</div>` : this.form.fields.map((t) => this.renderPreviewField(t))}
            </div>`;
  }
  renderPreviewField(t) {
    const e = a`<label class="pv-label">${t.label || t.id}${t.required ? a`<span class="pv-req">*</span>` : c}</label>`, s = this.renderPreviewControl(t);
    return a`
            <div class="pv-field">
                ${e}
                ${s}
                ${t.description ? a`<div class="pv-hint">${t.description}</div>` : c}
            </div>`;
  }
  /** Maps dataType + stereotype to the closest real input, so the preview reads like the form. */
  renderPreviewControl(t) {
    const e = t.stereotype ?? S, s = t.label || t.id;
    if (t.dataType === "bool" || e === "checkbox" || e === "toggle")
      return a`<input class="pv-check" type="checkbox" disabled/>`;
    if (e === "textarea" || e === "richText" || e === "html" || e === "markdown" || t.dataType === "component")
      return a`<textarea class="pv-inp" rows="3" disabled placeholder="${s}"></textarea>`;
    if (t.optionsSource)
      return a`<select class="pv-inp" disabled><option>${t.optionsSource.url ? "From " + t.optionsSource.url : "From a REST endpoint…"}</option></select>`;
    const i = (t.options ?? []).filter((o) => o?.value);
    if (e === "select" || e === "combobox" || e === "listBox" || e === "choice" || e === "menu" || t.dataType === "status" || t.dataType === "menu")
      return a`<select class="pv-inp" disabled>
                ${i.length === 0 ? a`<option>Select…</option>` : i.map((o) => a`<option>${o.label || o.value}</option>`)}
            </select>`;
    if (e === "radio") {
      const o = i.length === 0 ? [{ value: "a", label: "Option A" }, { value: "b", label: "Option B" }] : i;
      return a`<div class="pv-radio">${o.map((n) => a`<label><input type="radio" disabled/> ${n.label || n.value}</label>`)}</div>`;
    }
    if (e === "slider" || e === "range" || t.dataType === "range")
      return a`<input class="pv-inp" type="range" disabled/>`;
    if (e === "color" || t.dataType === "status" && e === "color")
      return a`<input class="pv-inp" type="color" disabled/>`;
    if (e === "button" || t.dataType === "action" || t.dataType === "actionGroup")
      return a`<button class="pv-inp pv-btn" disabled>${s}</button>`;
    if (e === "stars")
      return a`<div class="pv-stars">★★★☆☆</div>`;
    if (t.dataType === "file" || e === "image")
      return a`<input class="pv-inp" type="file" disabled/>`;
    const r = t.dataType === "integer" || t.dataType === "number" || t.dataType === "money" ? "number" : t.dataType === "date" ? "date" : t.dataType === "time" ? "time" : t.dataType === "dateTime" ? "datetime-local" : t.dataType === "dateRange" ? "date" : e === "email" ? "email" : e === "password" ? "password" : e === "link" ? "url" : "text";
    return a`<input class="pv-inp" type="${r}" disabled placeholder="${s}"/>`;
  }
};
m.styles = [Be, pe`
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
        .choices { margin-top: .5rem; padding-top: .4rem; border-top: 1px dashed var(--ec-border); }
        .choice-row { display: flex; align-items: center; gap: .3rem; margin-bottom: .25rem; }
        .choice-row .inp { flex: 1; min-width: 0; }
        .inp.mode { width: auto; margin: 0; padding: .1rem .3rem; font-size: .72rem; }
        .hint { font-size: .72rem; color: var(--ec-text-dim); margin-top: .35rem; }

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
f([
  F()
], m.prototype, "value", 2);
f([
  F({ type: Boolean })
], m.prototype, "readOnly", 2);
f([
  F({ type: Boolean, attribute: "no-expand" })
], m.prototype, "noExpand", 2);
f([
  F({ type: Boolean, reflect: !0 })
], m.prototype, "dark", 2);
f([
  L()
], m.prototype, "form", 2);
f([
  L()
], m.prototype, "editingId", 2);
f([
  L()
], m.prototype, "showPreview", 2);
f([
  L()
], m.prototype, "fullscreen", 2);
m = f([
  ge("eventconductor-form-editor")
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
    dataType: $e.includes(t?.dataType) ? t.dataType : "string",
    stereotype: t?.stereotype ?? void 0,
    required: t?.required ?? void 0,
    description: t?.description ?? void 0,
    options: Array.isArray(t?.options) ? t.options.map((e) => ({ value: e?.value ?? "", label: e?.label ?? void 0 })) : void 0,
    optionsSource: t?.optionsSource ? { ...t.optionsSource } : void 0
  };
}
function Xe(t) {
  const e = { name: t.name ?? "", fields: (t.fields ?? []).map(Qe) };
  return t.id && (e.id = t.id), t.description != null && t.description !== "" && (e.description = t.description), e;
}
function Qe(t) {
  const e = { id: t.id ?? "", label: t.label ?? "", dataType: t.dataType ?? "string" };
  t.stereotype != null && t.stereotype !== "" && (e.stereotype = t.stereotype), t.required && (e.required = !0), t.description != null && t.description !== "" && (e.description = t.description);
  const s = (t.options ?? []).filter((r) => r.value).map((r) => r.label != null && r.label !== "" && r.label !== r.value ? { value: r.value, label: r.label } : { value: r.value });
  s.length > 0 && (e.options = s);
  const i = t.optionsSource;
  if (i?.url) {
    const r = { url: i.url };
    i.method && (r.method = i.method), i.headers && Object.keys(i.headers).length > 0 && (r.headers = i.headers), i.body && (r.body = i.body), i.itemsPath && (r.itemsPath = i.itemsPath), i.valuePath && (r.valuePath = i.valuePath), i.labelPath && (r.labelPath = i.labelPath), i.proxy && (r.proxy = !0), e.optionsSource = r, delete e.options;
  }
  return e;
}
export {
  m as EventConductorFormEditor
};

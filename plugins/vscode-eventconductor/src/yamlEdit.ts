/**
 * Lossless write-back of a visual edit into a YAML definition file.
 *
 * The graph and form editors speak JSON: every change arrives as the whole definition. Dumping that
 * object over the file (what both IDE plugins used to do) rewrote every line on the first palette
 * drag — comments gone, values re-quoted, keys re-indented. This module instead compares the new
 * value with the file's own syntax tree (the `yaml` package's Document, which records where every
 * node sits in the source) and changes only the text of the nodes whose value actually changed:
 *
 * - a changed scalar is replaced in place, keeping its quoting style (`"x"`, `'x'`, plain, block);
 * - a key that went away is cut out with its lines (and the comment lines directly above it); a new
 *   key is appended after the last key of its mapping, at that mapping's indentation;
 * - sequence items are matched by `id` when every item has one (steps, preconditions), otherwise by
 *   position; removed items are cut out, new ones are inserted where they appear;
 * - flow collections (`{a: 1}`, `[a, b]`) that changed are rewritten as one flow node.
 *
 * Everything that did not change is left byte-for-byte as it was, comments included. The result is
 * re-parsed and compared with the requested value; if the targeted edit could not express the
 * change (anchors and aliases, complex keys, a reordered list), it falls back to rewriting only the
 * top-level keys that changed, and as a last resort to a full dump.
 *
 * Shared by both IDE plugins: the VS Code extension host imports it, and the same source is bundled
 * into the IntelliJ plugin's webview (`npm run bundle-intellij`, checked by the test suite).
 */
import {
  Document,
  isMap,
  isPair,
  isScalar,
  isSeq,
  parseDocument,
  Scalar,
  type Node as YamlNode,
  type Pair,
  type ToStringOptions,
  YAMLMap,
  YAMLSeq,
} from "yaml";

/** A definition body is YAML unless it clearly starts as JSON; an empty file is YAML. */
export function looksLikeYaml(text: string): boolean {
  const t = text.trimStart();
  if (!t) return true;
  return !(t.startsWith("{") || t.startsWith("["));
}

/** Parses a definition file (YAML or JSON) into plain data — the same reading the write-back uses. */
export function parseDefinition(text: string): unknown {
  if (!looksLikeYaml(text)) return JSON.parse(text);
  const doc = parseDocument(text);
  if (doc.errors.length) throw doc.errors[0];
  return doc.toJS();
}

/**
 * The file text that says `value`, changed from `text` as little as possible. JSON files (which hold
 * no comments) are pretty-printed whole, as before.
 */
export function applyDefinitionEdit(text: string, value: unknown): string {
  const target = plain(value);
  if (!looksLikeYaml(text)) return JSON.stringify(target, null, 2) + "\n";
  if (!text.trim()) return dump(target, defaultStyle());
  let doc: Document.Parsed;
  try {
    doc = parseDocument(text);
  } catch {
    return dump(target, defaultStyle());
  }
  if (doc.errors.length) return dump(target, defaultStyle());
  if (deepEqual(doc.toJS(), target)) return text;
  const style = detectStyle(text, doc);
  try {
    const edited = new TextPatcher(text, style).patchRoot(doc, target);
    if (edited !== undefined && says(edited, target)) return edited;
  } catch {
    /* fall through to the coarser rewrites */
  }
  const coarse = topLevelRewrite(doc, target, style);
  if (coarse !== undefined && says(coarse, target)) return coarse;
  return dump(target, style);
}

// ── Style of the file ──────────────────────────────────────────────────────────────────────────

interface Style {
  indent: number;
  indentSeq: boolean;
}

function defaultStyle(): Style {
  return { indent: 2, indentSeq: true };
}

function toStringOptions(style: Style, flow = false): ToStringOptions {
  return {
    indent: style.indent,
    indentSeq: style.indentSeq,
    lineWidth: 0,
    ...(flow ? { collectionStyle: "flow" as const } : {}),
  };
}

/** How the file indents: the step of its first nested mapping, and whether `- ` sits under or past the key. */
function detectStyle(text: string, doc: Document.Parsed): Style {
  const style = defaultStyle();
  let foundIndent = false;
  let foundSeq = false;
  const visit = (node: unknown) => {
    if (foundIndent && foundSeq) return;
    if (isMap(node) && !node.flow) {
      for (const pair of node.items) {
        const key = pair.key as YamlNode | null;
        const val = pair.value as YamlNode | null;
        if (!key?.range || !val?.range) continue;
        const keyCol = column(text, key.range[0]);
        if (!foundIndent && isMap(val) && !val.flow && val.items.length) {
          const childKey = val.items[0].key as YamlNode | null;
          if (childKey?.range) {
            const step = column(text, childKey.range[0]) - keyCol;
            if (step > 0) { style.indent = step; foundIndent = true; }
          }
        }
        if (!foundSeq && isSeq(val) && !val.flow && val.items.length) {
          style.indentSeq = column(text, val.range[0]) > keyCol;
          foundSeq = true;
        }
        visit(val);
      }
    } else if (isSeq(node) && !node.flow) {
      for (const item of node.items) visit(item);
    }
  };
  visit(doc.contents);
  return style;
}

// ── Targeted text edits ────────────────────────────────────────────────────────────────────────

interface Edit {
  start: number;
  end: number;
  text: string;
}

/** Thrown when a targeted edit cannot express the change; the caller falls back. */
class Unpatchable extends Error {}

class TextPatcher {
  private readonly edits: Edit[] = [];

  constructor(private readonly text: string, private readonly style: Style) {}

  patchRoot(doc: Document.Parsed, target: unknown): string | undefined {
    const root = doc.contents;
    if (!isMap(root) || root.flow || !isPlainObject(target)) return undefined;
    if (!root.items.length || !Object.keys(target).length) return undefined;
    this.patchMap(root, target);
    return this.apply();
  }

  private apply(): string {
    const sorted = [...this.edits].sort((a, b) => a.start - b.start || a.end - b.end);
    for (let i = 1; i < sorted.length; i++) {
      if (sorted[i].start < sorted[i - 1].end) throw new Unpatchable("overlapping edits");
    }
    let out = this.text;
    for (let i = sorted.length - 1; i >= 0; i--) {
      const e = sorted[i];
      out = out.slice(0, e.start) + e.text + out.slice(e.end);
    }
    return out;
  }

  // Maps ────────────────────────────────────────────────────────────────────

  private patchMap(map: YAMLMap, target: Record<string, unknown>) {
    const seen = new Set<string>();
    const pairs = map.items as Pair<YamlNode | null, YamlNode | null>[];
    pairs.forEach((pair, index) => {
      const key = keyOf(pair);
      if (key === undefined) throw new Unpatchable("complex key");
      if (seen.has(key)) throw new Unpatchable("duplicate key");
      seen.add(key);
      if (!Object.prototype.hasOwnProperty.call(target, key)) {
        this.deletePair(pairs, index);
        return;
      }
      this.patchPairValue(pair, target[key]);
    });
    const added = Object.keys(target).filter((k) => !seen.has(k));
    if (!added.length) return;
    const last = pairs[pairs.length - 1];
    const keyNode = last.key as YamlNode;
    const col = column(this.text, keyNode.range![0]);
    const at = this.blockEnd(pairEnd(last));
    const body = added.map((k) => " ".repeat(col) + this.renderBlock({ [k]: target[k] }, col)).join("\n");
    this.insertLines(at, body);
  }

  private patchPairValue(pair: Pair<YamlNode | null, YamlNode | null>, target: unknown) {
    const node = pair.value;
    if (node && deepEqual(node.toJS(new Document()), target)) return;
    // A value's own continuation lines (a block scalar's) sit one step in from its key.
    const contCol = column(this.text, (pair.key as YamlNode).range![0]) + this.style.indent;
    if (!this.patchValue(node, target, contCol)) this.rewritePair(pair, target);
  }

  /** Removes one key with its lines, and the comment lines right above it. */
  private deletePair(pairs: Pair<YamlNode | null, YamlNode | null>[], index: number) {
    const pair = pairs[index];
    const keyStart = (pair.key as YamlNode).range![0];
    const lineStart = this.lineStart(keyStart);
    const end = this.blockEnd(pairEnd(pair));
    if (this.text.slice(lineStart, keyStart).trim() !== "") {
      // The first key of a compact `- key: value` item: the next key moves up behind the dash.
      const next = pairs[index + 1];
      if (!next) throw new Unpatchable("last key of a compact item");
      this.edits.push({ start: keyStart, end: (next.key as YamlNode).range![0], text: "" });
      return;
    }
    this.cutLines(this.withLeadingComments(lineStart), end);
  }

  /** Rewrites `key: value` whole, at the key's place — for changes a value-only edit cannot carry. */
  private rewritePair(pair: Pair<YamlNode | null, YamlNode | null>, target: unknown) {
    const keyNode = pair.key as YamlNode;
    const key = keyOf(pair)!;
    const start = keyNode.range![0];
    const end = this.blockEnd(pairEnd(pair));
    const col = column(this.text, start);
    this.edits.push({ start, end, text: this.renderBlock({ [key]: target }, col) + "\n" });
  }

  // Values ──────────────────────────────────────────────────────────────────

  /** Edits a value in place; false when it cannot, so the caller rewrites its whole pair or item. */
  private patchValue(node: YamlNode | null, target: unknown, contCol: number): boolean {
    if (!node || !node.range) return false;
    if (isScalar(node)) {
      if (target !== null && typeof target === "object") return false;
      this.replaceScalar(node, target, contCol);
      return true;
    }
    if (isMap(node)) {
      if (!isPlainObject(target)) return false;
      if (node.flow) { this.replaceFlow(node, target); return true; }
      if (!node.items.length || !Object.keys(target).length) return false;
      this.patchMap(node, target);
      return true;
    }
    if (isSeq(node)) {
      if (!Array.isArray(target)) return false;
      if (node.flow) { this.replaceFlow(node, target); return true; }
      if (!node.items.length || !target.length) return false;
      return this.patchSeq(node, target);
    }
    return false; // alias or anything else: rewrite around it
  }

  private replaceScalar(node: Scalar, target: unknown, contCol: number) {
    const start = node.range![0];
    let end = node.range![1];
    const scalar = new Scalar(target);
    if (typeof target === "string" && typeof node.value === "string") {
      // Keep the author's quoting. A block scalar stays a block only while it holds lines.
      const type = node.type;
      const multiline = target.includes("\n");
      if ((type === Scalar.QUOTE_DOUBLE || type === Scalar.QUOTE_SINGLE) && !multiline) scalar.type = type;
      else if ((type === Scalar.BLOCK_LITERAL || type === Scalar.BLOCK_FOLDED) && multiline) scalar.type = type;
    }
    let rendered = this.renderBlock(scalar, contCol);
    const endsLine = end > start && this.text[end - 1] === "\n";
    if (endsLine) {
      // A block scalar's range runs to the start of the next line; keep that line break.
      rendered += "\n";
    } else if (rendered.includes("\n")) {
      // Now a block scalar: it has to end its line, so whatever followed on it (a comment) goes.
      end = this.nextLineStart(end);
      rendered += "\n";
    }
    this.edits.push({ start, end, text: rendered });
  }

  private replaceFlow(node: YAMLMap | YAMLSeq, target: unknown) {
    const [start, end] = node.range!;
    const doc = new Document(target);
    // `{a: 1}` or `{ a: 1 }`: written the way this node was.
    const padded = /^[[{]\s/.test(this.text.slice(start, end));
    const rendered = doc.toString({ ...toStringOptions(this.style, true), flowCollectionPadding: padded }).replace(/\n$/, "");
    if (rendered.includes("\n")) throw new Unpatchable("multi-line flow rendering");
    this.edits.push({ start, end, text: rendered });
  }

  // Sequences ───────────────────────────────────────────────────────────────

  private patchSeq(seq: YAMLSeq, target: unknown[]): boolean {
    const items = seq.items as (YamlNode | null)[];
    if (items.some((it) => !it || !it.range)) return false;
    const oldJs = items.map((it) => it!.toJS(new Document()));
    const match = matchItems(oldJs, target);
    if (!match) return false;
    // match[j] = old index for target[j], or -1 for a new item; matched old indices must keep order.
    let prev = -1;
    for (const m of match) {
      if (m < 0) continue;
      if (m <= prev) return false;
      prev = m;
    }
    const kept = new Set(match.filter((m) => m >= 0));
    items.forEach((item, i) => {
      if (!kept.has(i)) this.deleteItem(items, i);
    });
    const dashCol = column(this.text, this.dashOf(items[0]!));
    // New items go before the next surviving item, or after the last item when none follows.
    let pending: unknown[] = [];
    const flushBefore = (oldIndex: number | null) => {
      if (!pending.length) return;
      const rendered = pending.map((v) => " ".repeat(dashCol) + "- " + this.renderBlock(v, dashCol + 2));
      pending = [];
      // Items the author spaced out with blank lines get the same space.
      const spaced = items.length > 1 && this.blankLineBefore(items[items.length - 1]!);
      const body = rendered.join(spaced ? "\n\n" : "\n");
      if (oldIndex === null) {
        this.insertLines(this.blockEnd(items[items.length - 1]!.range![1]), (spaced ? "\n" : "") + body);
      } else {
        const at = this.withLeadingComments(this.lineStart(this.dashOf(items[oldIndex]!)));
        this.edits.push({ start: at, end: at, text: body + (spaced ? "\n\n" : "\n") });
      }
    };
    target.forEach((value, j) => {
      const m = match[j];
      if (m < 0) { pending.push(value); return; }
      flushBefore(m);
      const node = items[m]!;
      if (deepEqual(oldJs[m], value)) return;
      if (!this.patchValue(node, value, column(this.text, node.range![0]))) this.rewriteItem(node, value);
    });
    flushBefore(null);
    return true;
  }

  private deleteItem(items: (YamlNode | null)[], index: number) {
    const node = items[index]!;
    const start = this.withLeadingComments(this.lineStart(this.dashOf(node)));
    this.cutLines(start, this.blockEnd(node.range![1]));
  }

  private rewriteItem(node: YamlNode, value: unknown) {
    const start = node.range![0];
    const end = this.blockEnd(node.range![1]);
    const col = column(this.text, start);
    this.edits.push({ start, end, text: this.renderBlock(value, col) + "\n" });
  }

  /** The offset of the `- ` introducing a sequence item. */
  private dashOf(node: YamlNode): number {
    let i = node.range![0] - 1;
    while (i >= 0 && /\s/.test(this.text[i])) i--;
    if (this.text[i] !== "-") throw new Unpatchable("item without a dash");
    return i;
  }

  // Text helpers ────────────────────────────────────────────────────────────

  private lineStart(pos: number): number {
    return this.text.lastIndexOf("\n", pos - 1) + 1;
  }

  /** The start of the line after the one holding `pos` (or `pos` itself when it already starts a line). */
  private nextLineStart(pos: number): number {
    if (pos > 0 && this.text[pos - 1] === "\n") return pos;
    const nl = this.text.indexOf("\n", pos);
    return nl < 0 ? this.text.length : nl + 1;
  }

  /**
   * Where the lines of a node ending at `pos` stop: the start of the next line, with trailing blank
   * lines given back to whatever follows (the parser counts them into the collection before them).
   */
  private blockEnd(pos: number): number {
    let end = this.nextLineStart(pos);
    while (end > 0) {
      const prevStart = this.text.lastIndexOf("\n", end - 2) + 1;
      if (prevStart >= end || this.text.slice(prevStart, end).trim() !== "") break;
      end = prevStart;
    }
    return end;
  }

  /** Cuts whole lines; when blank lines sit on both sides of the cut, one of them goes too. */
  private cutLines(start: number, end: number) {
    const before = start > 0 ? this.text.slice(this.text.lastIndexOf("\n", start - 2) + 1, start) : "x";
    const nl = this.text.indexOf("\n", end);
    const after = end < this.text.length ? this.text.slice(end, nl < 0 ? this.text.length : nl + 1) : "x";
    if (before.trim() === "" && after.trim() === "" && start > 0) end = nl < 0 ? this.text.length : nl + 1;
    this.edits.push({ start, end, text: "" });
  }

  /** Whether a blank line separates this item (with its comments) from what precedes it. */
  private blankLineBefore(node: YamlNode): boolean {
    const start = this.withLeadingComments(this.lineStart(this.dashOf(node)));
    if (start === 0) return false;
    const prevStart = this.text.lastIndexOf("\n", start - 2) + 1;
    return this.text.slice(prevStart, start).trim() === "";
  }

  /** Extends a line start upwards over the comment lines directly above it (no blank line between). */
  private withLeadingComments(lineStart: number): number {
    let start = lineStart;
    while (start > 0) {
      const prevStart = this.text.lastIndexOf("\n", start - 2) + 1;
      const line = this.text.slice(prevStart, start - 1);
      if (!line.trimStart().startsWith("#")) break;
      start = prevStart;
    }
    return start;
  }

  /** Inserts whole lines at a line start (or at the end of a file that lacks a final newline). */
  private insertLines(at: number, body: string) {
    if (at >= this.text.length && this.text.length && !this.text.endsWith("\n")) {
      this.edits.push({ start: this.text.length, end: this.text.length, text: "\n" + body + "\n" });
    } else {
      this.edits.push({ start: at, end: at, text: body + "\n" });
    }
  }

  /** `value` as YAML whose first line goes where the cursor is and whose other lines sit at `col`. */
  private renderBlock(value: unknown, col: number): string {
    const doc = new Document();
    doc.contents = value instanceof Scalar ? value : doc.createNode(value);
    const out = doc.toString(toStringOptions(this.style)).replace(/\n$/, "");
    const pad = " ".repeat(col);
    return out.split("\n").map((line, i) => (i === 0 || line === "" ? line : pad + line)).join("\n");
  }
}

function pairEnd(pair: Pair<YamlNode | null, YamlNode | null>): number {
  const value = pair.value;
  if (value?.range) return value.range[1];
  return (pair.key as YamlNode).range![1];
}

function keyOf(pair: Pair<unknown, unknown>): string | undefined {
  const key = pair.key;
  if (!isScalar(key) || !key.range) return undefined;
  const v = key.value;
  if (typeof v === "string") return v;
  if (typeof v === "number" || typeof v === "boolean") return String(v);
  return undefined;
}

/** Old→new correspondence of list items: by `id` when every item carries a distinct one, else by position. */
function matchItems(oldJs: unknown[], target: unknown[]): number[] | undefined {
  const ids = (list: unknown[]) => {
    const out: string[] = [];
    for (const it of list) {
      if (!isPlainObject(it) || typeof it.id !== "string") return undefined;
      out.push(it.id);
    }
    return new Set(out).size === out.length ? out : undefined;
  };
  const oldIds = ids(oldJs);
  const newIds = ids(target);
  if (oldIds && newIds) return newIds.map((id) => oldIds.indexOf(id));
  return target.map((_, j) => (j < oldJs.length ? j : -1));
}

function column(text: string, pos: number): number {
  return pos - (text.lastIndexOf("\n", pos - 1) + 1);
}

// ── Coarse fallbacks ───────────────────────────────────────────────────────────────────────────

/** Rewrites only the top-level keys whose value changed, through the Document API (keeps the rest's comments). */
function topLevelRewrite(doc: Document.Parsed, target: unknown, style: Style): string | undefined {
  try {
    const root = doc.contents;
    if (!isMap(root) || !isPlainObject(target)) return undefined;
    const copy = doc.clone();
    const map = copy.contents as YAMLMap;
    for (const pair of [...map.items]) {
      const key = keyOf(pair);
      if (key === undefined) return undefined;
      if (!Object.prototype.hasOwnProperty.call(target, key)) map.delete(pair.key);
    }
    for (const [key, value] of Object.entries(target)) {
      const current = map.get(key, true) as YamlNode | undefined;
      if (current && deepEqual(current.toJS?.(copy) ?? current, value)) continue;
      map.set(key, copy.createNode(value));
    }
    return copy.toString(toStringOptions(style));
  } catch {
    return undefined;
  }
}

function dump(value: unknown, style: Style): string {
  return new Document(value).toString(toStringOptions(style));
}

// ── Values ─────────────────────────────────────────────────────────────────────────────────────

/** Whether `text` parses, as YAML, to exactly `target`. */
function says(text: string, target: unknown): boolean {
  try {
    const doc = parseDocument(text);
    return !doc.errors.length && deepEqual(doc.toJS(), target);
  } catch {
    return false;
  }
}

/** The JSON view of a value (what the editors send): undefined keys dropped, dates as strings. */
function plain(value: unknown): unknown {
  return value === undefined ? null : JSON.parse(JSON.stringify(value));
}

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

/** Structural equality, ignoring key order. */
function deepEqual(a: unknown, b: unknown): boolean {
  if (a === b) return true;
  if (a === undefined) a = null;
  if (b === undefined) b = null;
  if (a === b) return true;
  if (typeof a !== typeof b || a === null || b === null || typeof a !== "object") return false;
  if (Array.isArray(a) !== Array.isArray(b)) return false;
  if (Array.isArray(a)) {
    const bb = b as unknown[];
    return a.length === bb.length && a.every((x, i) => deepEqual(x, bb[i]));
  }
  const ao = a as Record<string, unknown>;
  const bo = b as Record<string, unknown>;
  const ak = Object.keys(ao).filter((k) => ao[k] !== undefined);
  const bk = Object.keys(bo).filter((k) => bo[k] !== undefined);
  return ak.length === bk.length && ak.every((k) => Object.prototype.hasOwnProperty.call(bo, k) && deepEqual(ao[k], bo[k]));
}

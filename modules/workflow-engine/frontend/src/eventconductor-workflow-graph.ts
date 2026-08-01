import {customElement, property, state} from "lit/decorators.js";
import {css, html, LitElement, nothing, svg} from "lit";
import type {ELK, ElkNode, ElkExtendedEdge} from "elkjs/lib/elk.bundled.js";
import {neutralButtonStyles, iconCog, iconPlus, iconDownload, iconSitemap} from "./neutralChrome";

// ── Domain types ─────────────────────────────────────────────────────────────

type StepType =
    | "START" | "ACTION" | "USER_TASK" | "RULE" | "TIMER"
    | "WAIT_FOR_MESSAGE" | "SEND_MESSAGE" | "FORK" | "JOIN" | "PROCESS" | "END";
type WorkflowStatus = "DRAFT" | "ACTIVE" | "DISABLED" | "ARCHIVED";

interface WorkflowStep {
    id: string;
    type: StepType;
    name: string;
    description?: string;
    preconditionStepId?: string;
    preconditionStepIds?: string[];
    preconditionExpression?: string;
    parallel?: boolean;
    topic?: string;
    formId?: string;
    ruleId?: string;
    messageName?: string;
    childWorkflowDefinitionId?: string;
    timeout?: number;
    retries?: number;
    rollbackable?: boolean;
    compensationStepId?: string;
}

interface WorkflowDefinition {
    id?: string;
    name: string;
    version?: number;
    description?: string;
    status?: WorkflowStatus;
    limitConcurrentExecutions?: boolean;
    maxConcurrentExecutions?: number;
    enqueueOnLimit?: boolean;
    steps: WorkflowStep[];
}

interface NodePos { x: number; y: number; }
interface Pt { x: number; y: number; }
/** A node's geometry as center + size — the shape the router works in. */
interface Box { x: number; y: number; w: number; h: number; }

// ── Constants ─────────────────────────────────────────────────────────────────

const NODE_W = 176;
const NODE_H = 60;
const PAD = 60;

const STEP_TYPES: StepType[] = [
    "START", "ACTION", "USER_TASK", "RULE", "TIMER",
    "WAIT_FOR_MESSAGE", "SEND_MESSAGE", "FORK", "JOIN", "PROCESS", "END",
];

/**
 * Per-type visual identity — fill, stroke and a corner glyph — echoing modux's workflow
 * palette. Type colours are intentionally literal (like modux's view adapters); the canvas,
 * text and edges use the themeable `--ec-*` custom properties instead so the component dresses
 * like its host (light / Lumo dark).
 */
interface NodeStyle { fill: string; stroke: string; symbol: string; dashed?: boolean; }
const NODE_STYLE: Record<StepType, NodeStyle> = {
    // BPMN events: start = thin green circle, end = thick red circle.
    START:            {fill: "#f0fdf4", stroke: "#16a34a", symbol: "flow"},
    ACTION:           {fill: "#ffffff", stroke: "#6d28d9", symbol: "process"},
    USER_TASK:        {fill: "#fef9c3", stroke: "#ca8a04", symbol: "person"},
    RULE:             {fill: "#ffffff", stroke: "#4f46e5", symbol: "operation"},
    TIMER:            {fill: "#ffffff", stroke: "#d97706", symbol: "clock"},
    WAIT_FOR_MESSAGE: {fill: "#ffffff", stroke: "#0891b2", symbol: "event"},
    SEND_MESSAGE:     {fill: "#ffffff", stroke: "#0891b2", symbol: "flow"},
    // BPMN parallel gateways: amber diamonds with a "+".
    FORK:             {fill: "#fffbeb", stroke: "#b45309", symbol: "flow"},
    JOIN:             {fill: "#fffbeb", stroke: "#b45309", symbol: "flow"},
    PROCESS:          {fill: "#eef2ff", stroke: "#4f46e5", symbol: "component"},
    END:              {fill: "#fef2f2", stroke: "#dc2626", symbol: "event"},
};
const DEFAULT_STYLE: NodeStyle = {fill: "#ffffff", stroke: "#94a3b8", symbol: "process"};
const styleOf = (t: StepType): NodeStyle => NODE_STYLE[t] ?? DEFAULT_STYLE;

/** BPMN events (START/END) and gateways (FORK/JOIN) are compact squares; the rest are tasks. */
const EVENT_SIZE = 56;
function isEventType(t: StepType): boolean { return t === "START" || t === "END"; }
function isGatewayType(t: StepType): boolean { return t === "FORK" || t === "JOIN"; }
function sizeOf(t: StepType): {w: number; h: number} {
    return (isEventType(t) || isGatewayType(t)) ? {w: EVENT_SIZE, h: EVENT_SIZE} : {w: NODE_W, h: NODE_H};
}

/**
 * ArchiMate-inspired glyphs (ported from modux), each fitting a 12×12 box, stroke-only — drawn
 * in the node's top-right corner in the node's own stroke colour.
 */
const SYMBOLS: Record<string, ReturnType<typeof svg>> = {
    flow:      svg`<path d="M0.5 6 H8"/><path d="M5.5 2.5 L9.5 6 L5.5 9.5"/>`,
    process:   svg`<path d="M0.5 3 H7 V0.8 L11.5 6 L7 11.2 V9 H0.5 Z"/>`,
    person:    svg`<circle cx="6" cy="3.2" r="2.4"/><path d="M1.5 11.5 C1.5 7.6, 10.5 7.6, 10.5 11.5"/>`,
    operation: svg`<circle cx="6" cy="6" r="2.4"/><path d="M6 0.8 V2.6 M6 9.4 V11.2 M0.8 6 H2.6 M9.4 6 H11.2" stroke-linecap="round"/>`,
    clock:     svg`<circle cx="6" cy="6" r="4.4"/><path d="M6 3.4 L6 6 L7.9 7.4" stroke-linecap="round"/>`,
    event:     svg`<circle cx="6" cy="6" r="5"/><circle cx="6" cy="6" r="2.6"/>`,
    component: svg`<rect x="3.5" y="0.5" width="8" height="11" rx="1"/><rect x="0.5" y="2.5" width="6" height="2.6"/><rect x="0.5" y="6.9" width="6" height="2.6"/>`,
};

/** Short caption shown above each node — the step's salient reference, modux-style. */
function badgeOf(step: WorkflowStep): string {
    switch (step.type) {
        case "ACTION": return step.topic ? "→ " + step.topic : "ACTION";
        case "USER_TASK": return "👤 " + (step.formId || "form");
        case "RULE": return "ƒ " + (step.ruleId || "rule");
        case "WAIT_FOR_MESSAGE": return "✉ " + (step.messageName || "message");
        case "SEND_MESSAGE": return "✉→ " + (step.messageName || "message");
        case "FORK": return "⑃ FORK";
        case "JOIN": return "⨝ JOIN";
        case "PROCESS": return "⚙ " + (step.childWorkflowDefinitionId || "subprocess");
        default: return step.type; // START, TIMER, END
    }
}

// ── Edge routing (ported from modux) ───────────────────────────────────────────

/** Point on the border of `box` along the line from its center towards (tx, ty). */
function borderTowards(box: Box, tx: number, ty: number): Pt {
    const dx = tx - box.x, dy = ty - box.y;
    if (dx === 0 && dy === 0) return {x: box.x, y: box.y};
    const scale = 1 / Math.max(Math.abs(dx) / (box.w / 2), Math.abs(dy) / (box.h / 2));
    return {x: box.x + dx * scale, y: box.y + dy * scale};
}

function straightRoute(a: Box, b: Box, spread: number): Pt[] {
    let p0 = borderTowards(a, b.x, b.y);
    let p1 = borderTowards(b, a.x, a.y);
    if (spread !== 0) {
        const len = Math.hypot(p1.x - p0.x, p1.y - p0.y) || 1;
        const nx = (-(p1.y - p0.y) / len) * spread;
        const ny = ((p1.x - p0.x) / len) * spread;
        p0 = {x: p0.x + nx, y: p0.y + ny};
        p1 = {x: p1.x + nx, y: p1.y + ny};
    }
    return [p0, p1];
}

/** Orthogonal route between two node boxes; `spread` separates edges sharing a node pair. */
function orthogonalRoute(a: Box, b: Box, spread = 0): Pt[] {
    const dx = b.x - a.x, dy = b.y - a.y, EPS = 0.5;
    if (Math.abs(dx) <= EPS || Math.abs(dy) <= EPS) return straightRoute(a, b, spread);
    const gapX = dx > 0 ? b.x - b.w / 2 - (a.x + a.w / 2) : a.x - a.w / 2 - (b.x + b.w / 2);
    const gapY = dy > 0 ? b.y - b.h / 2 - (a.y + a.h / 2) : a.y - a.h / 2 - (b.y + b.h / 2);
    const horizontalPreferred = Math.abs(dx) >= Math.abs(dy);
    const horizontal = () => {
        const p0 = {x: a.x + (Math.sign(dx) * a.w) / 2, y: a.y + spread};
        const p1 = {x: b.x - (Math.sign(dx) * b.w) / 2, y: b.y + spread};
        const midX = (p0.x + p1.x) / 2 + spread;
        return [p0, {x: midX, y: p0.y}, {x: midX, y: p1.y}, p1];
    };
    const vertical = () => {
        const p0 = {x: a.x + spread, y: a.y + (Math.sign(dy) * a.h) / 2};
        const p1 = {x: b.x + spread, y: b.y - (Math.sign(dy) * b.h) / 2};
        const midY = (p0.y + p1.y) / 2 + spread;
        return [p0, {x: p0.x, y: midY}, {x: p1.x, y: midY}, p1];
    };
    if (gapX >= 0 && (horizontalPreferred || gapY < 0)) return horizontal();
    if (gapY >= 0) return vertical();
    if (gapX >= 0) return horizontal();
    return straightRoute(a, b, spread);
}

/** Does the segment a→b clip the axis-aligned box (centre + size)? Liang–Barsky. */
function segmentCrossesBox(a: Pt, b: Pt, box: Box): boolean {
    const minX = box.x - box.w / 2, maxX = box.x + box.w / 2;
    const minY = box.y - box.h / 2, maxY = box.y + box.h / 2;
    let t0 = 0, t1 = 1;
    const dx = b.x - a.x, dy = b.y - a.y;
    for (const [p, q] of [[-dx, a.x - minX], [dx, maxX - a.x], [-dy, a.y - minY], [dy, maxY - a.y]] as [number, number][]) {
        if (p === 0) { if (q < 0) return false; continue; }
        const r = q / p;
        if (p < 0) { if (r > t1) return false; if (r > t0) t0 = r; }
        else { if (r < t0) return false; if (r < t1) t1 = r; }
    }
    return t1 - t0 > 0.02; // a mere corner graze does not count
}

/** Border exit of `box` aligned to (tx,ty)'s perpendicular coord, so the stub stays orthogonal. */
function orthoBorder(box: Box, tx: number, ty: number): Pt {
    const dx = tx - box.x, dy = ty - box.y, hw = box.w / 2, hh = box.h / 2;
    if (Math.abs(dx) >= Math.abs(dy) && Math.abs(dy) <= hh) return {x: box.x + Math.sign(dx) * hw, y: ty};
    if (Math.abs(dy) >= Math.abs(dx) && Math.abs(dx) <= hw) return {x: tx, y: box.y + Math.sign(dy) * hh};
    if (dx === 0 && dy === 0) return {x: box.x, y: box.y};
    const scale = 1 / Math.max(Math.abs(dx) / hw, Math.abs(dy) / hh);
    return {x: box.x + dx * scale, y: box.y + dy * scale};
}

/**
 * Orthogonal route between two boxes that steers around the other nodes (obstacles), so no
 * line ever runs across a node. Ported from modux: if the default route is clean it is kept;
 * otherwise a family of orthogonal detours (L-shapes and Z-channels, plus channels that clear
 * each nearby obstacle) is scored (boxes crossed dominate, then length + bends) and the best is
 * taken. Endpoints are re-anchored to the borders so the whole path stays horizontal/vertical.
 */
function routeAvoiding(src: Box, tgt: Box, obstacles: Box[], spread = 0, margin = 22): Pt[] {
    const crossings = (pts: Pt[]): number => {
        let n = 0;
        for (let i = 0; i < pts.length - 1; i++)
            for (const o of obstacles)
                if (segmentCrossesBox(pts[i], pts[i + 1], {x: o.x, y: o.y, w: o.w + 2 * margin, h: o.h + 2 * margin})) n++;
        return n;
    };
    const base = orthogonalRoute(src, tgt, spread);
    const baseCross = crossings(base);
    if (baseCross === 0) return base;

    const S = {x: src.x, y: src.y}, T = {x: tgt.x, y: tgt.y};
    const cands: Pt[][] = [[{x: T.x, y: S.y}], [{x: S.x, y: T.y}]]; // two L shapes
    for (const f of [0.5, 0.38, 0.62, 0.26, 0.74]) {
        const mx = S.x + (T.x - S.x) * f, my = S.y + (T.y - S.y) * f;
        cands.push([{x: mx, y: S.y}, {x: mx, y: T.y}]);
        cands.push([{x: S.x, y: my}, {x: T.x, y: my}]);
    }
    const loX = Math.min(S.x, T.x), hiX = Math.max(S.x, T.x), loY = Math.min(S.y, T.y), hiY = Math.max(S.y, T.y);
    for (const o of obstacles) {
        const m = margin + 8;
        if (o.x > loX - o.w && o.x < hiX + o.w) {
            cands.push([{x: S.x, y: o.y - o.h / 2 - m}, {x: T.x, y: o.y - o.h / 2 - m}]);
            cands.push([{x: S.x, y: o.y + o.h / 2 + m}, {x: T.x, y: o.y + o.h / 2 + m}]);
        }
        if (o.y > loY - o.h && o.y < hiY + o.h) {
            cands.push([{x: o.x - o.w / 2 - m, y: S.y}, {x: o.x - o.w / 2 - m, y: T.y}]);
            cands.push([{x: o.x + o.w / 2 + m, y: S.y}, {x: o.x + o.w / 2 + m, y: T.y}]);
        }
    }
    let best: Pt[] | null = null, bestScore = Infinity, bestCross = Infinity;
    for (const c of cands) {
        const full = [S, ...c, T];
        const cross = crossings(full);
        const score = cross * 1e6 + polylineLength(full) + c.length * 40;
        if (score < bestScore) { best = c; bestScore = score; bestCross = cross; }
    }
    if (best && bestCross < baseCross) {
        return [orthoBorder(src, best[0].x, best[0].y), ...best, orthoBorder(tgt, best[best.length - 1].x, best[best.length - 1].y)];
    }
    return base;
}

type Side = "R" | "L" | "T" | "B";
function stubOut(pt: Pt, side: Side, d: number): Pt {
    return side === "R" ? {x: pt.x + d, y: pt.y} : side === "L" ? {x: pt.x - d, y: pt.y}
        : side === "T" ? {x: pt.x, y: pt.y - d} : {x: pt.x, y: pt.y + d};
}

/**
 * Orthogonal route between two *specific border points* (each leaving its node perpendicular
 * to its side), avoiding the other nodes. Lets edges attach at distinct points on a node so
 * parallel edges never lie on top of one another. Same candidate-scoring idea as routeAvoiding.
 */
function routeThrough(sPt: Pt, sSide: Side, tPt: Pt, tSide: Side, obstacles: Box[], margin = 20): Pt[] {
    const STUB = 16;
    const S = stubOut(sPt, sSide, STUB), T = stubOut(tPt, tSide, STUB);
    const crossings = (pts: Pt[]): number => {
        let n = 0;
        for (let i = 0; i < pts.length - 1; i++)
            for (const o of obstacles)
                if (segmentCrossesBox(pts[i], pts[i + 1], {x: o.x, y: o.y, w: o.w + 2 * margin, h: o.h + 2 * margin})) n++;
        return n;
    };
    const cands: Pt[][] = [[{x: T.x, y: S.y}], [{x: S.x, y: T.y}]];
    for (const f of [0.5, 0.35, 0.65, 0.25, 0.75]) {
        const mx = S.x + (T.x - S.x) * f, my = S.y + (T.y - S.y) * f;
        cands.push([{x: mx, y: S.y}, {x: mx, y: T.y}]);
        cands.push([{x: S.x, y: my}, {x: T.x, y: my}]);
    }
    const loX = Math.min(S.x, T.x), hiX = Math.max(S.x, T.x), loY = Math.min(S.y, T.y), hiY = Math.max(S.y, T.y);
    for (const o of obstacles) {
        const m = margin + 8;
        if (o.x > loX - o.w && o.x < hiX + o.w) {
            cands.push([{x: S.x, y: o.y - o.h / 2 - m}, {x: T.x, y: o.y - o.h / 2 - m}]);
            cands.push([{x: S.x, y: o.y + o.h / 2 + m}, {x: T.x, y: o.y + o.h / 2 + m}]);
        }
        if (o.y > loY - o.h && o.y < hiY + o.h) {
            cands.push([{x: o.x - o.w / 2 - m, y: S.y}, {x: o.x - o.w / 2 - m, y: T.y}]);
            cands.push([{x: o.x + o.w / 2 + m, y: S.y}, {x: o.x + o.w / 2 + m, y: T.y}]);
        }
    }
    let best = cands[0], bestScore = Infinity;
    for (const c of cands) {
        const full = [sPt, S, ...c, T, tPt];
        const score = crossings(full) * 1e6 + polylineLength(full) + c.length * 40;
        if (score < bestScore) { best = c; bestScore = score; }
    }
    return [sPt, S, ...best, T, tPt];
}

/** Where do segments a→b and c→d cross (strictly interior)? Returns the point + its t on a→b. */
function segIntersect(a: Pt, b: Pt, c: Pt, d: Pt): (Pt & {t: number}) | null {
    const rx = b.x - a.x, ry = b.y - a.y, sx = d.x - c.x, sy = d.y - c.y;
    const denom = rx * sy - ry * sx;
    if (Math.abs(denom) < 1e-9) return null;
    const t = ((c.x - a.x) * sy - (c.y - a.y) * sx) / denom;
    const u = ((c.x - a.x) * ry - (c.y - a.y) * rx) / denom;
    if (t <= 0.02 || t >= 0.98 || u <= 0.02 || u >= 0.98) return null;
    return {x: a.x + t * rx, y: a.y + t * ry, t};
}

/** A straight run a→b that hops over each prior segment it crosses with a small arc (a wire bridge). */
function straightWithBridges(a: Pt, b: Pt, prior: [Pt, Pt][], radius: number): string {
    const len = Math.hypot(b.x - a.x, b.y - a.y) || 1;
    const ux = (b.x - a.x) / len, uy = (b.y - a.y) / len;
    const crossings = prior
        .map(([c, e]) => segIntersect(a, b, c, e))
        .filter((p): p is Pt & {t: number} => p !== null)
        .filter(p => p.t * len > radius + 2 && (1 - p.t) * len > radius + 2)
        .sort((p, q) => p.t - q.t);
    let d = "";
    let lastEnd = -Infinity;
    for (const p of crossings) {
        if (p.t * len - radius <= lastEnd + 2) continue; // merged with the previous hop
        d += ` L ${p.x - ux * radius} ${p.y - uy * radius}`;
        d += ` A ${radius} ${radius} 0 0 1 ${p.x + ux * radius} ${p.y + uy * radius}`;
        lastEnd = p.t * len + radius;
    }
    return d + ` L ${b.x} ${b.y}`;
}

/** Rounded-corner polyline path that also bridges (hops over) every prior segment it crosses. */
function bridgedPath(pts: Pt[], prior: [Pt, Pt][], cornerR = 9, bridgeR = 6): string {
    if (pts.length < 2) return pts.length ? `M ${pts[0].x} ${pts[0].y}` : "";
    let d = `M ${pts[0].x} ${pts[0].y}`;
    let from = pts[0];
    for (let i = 1; i < pts.length - 1; i++) {
        const p = pts[i], next = pts[i + 1];
        const dPrev = Math.hypot(p.x - from.x, p.y - from.y) || 1;
        const dNext = Math.hypot(next.x - p.x, next.y - p.y) || 1;
        const r = Math.min(cornerR, dPrev / 2, dNext / 2);
        const a1 = {x: p.x + ((from.x - p.x) / dPrev) * r, y: p.y + ((from.y - p.y) / dPrev) * r};
        const a2 = {x: p.x + ((next.x - p.x) / dNext) * r, y: p.y + ((next.y - p.y) / dNext) * r};
        d += straightWithBridges(from, a1, prior, bridgeR);
        d += ` Q ${p.x} ${p.y} ${a2.x} ${a2.y}`;
        from = a2;
    }
    return d + straightWithBridges(from, pts[pts.length - 1], prior, bridgeR);
}

/** The point at `frac` (0 = source … 1 = target) along a polyline — where an edge label sits. */
function polylinePointAt(pts: Pt[], frac = 0.5): Pt {
    let total = 0;
    for (let i = 0; i < pts.length - 1; i++) total += Math.hypot(pts[i + 1].x - pts[i].x, pts[i + 1].y - pts[i].y);
    let remaining = total * Math.min(Math.max(frac, 0), 1);
    for (let i = 0; i < pts.length - 1; i++) {
        const seg = Math.hypot(pts[i + 1].x - pts[i].x, pts[i + 1].y - pts[i].y);
        if (seg >= remaining && seg > 0) {
            const t = remaining / seg;
            return {x: pts[i].x + (pts[i + 1].x - pts[i].x) * t, y: pts[i].y + (pts[i + 1].y - pts[i].y) * t};
        }
        remaining -= seg;
    }
    return pts[Math.floor(pts.length / 2)];
}

/** Total length of a polyline. */
function polylineLength(pts: Pt[]): number {
    let total = 0;
    for (let i = 0; i < pts.length - 1; i++) total += Math.hypot(pts[i + 1].x - pts[i].x, pts[i + 1].y - pts[i].y);
    return total;
}

/** SVG path along a polyline with the interior corners rounded off. */
function roundedPath(pts: Pt[], r = 9): string {
    if (pts.length < 2) return "";
    let d = `M ${pts[0].x} ${pts[0].y}`;
    for (let i = 1; i < pts.length - 1; i++) {
        const p = pts[i], a = pts[i - 1], b = pts[i + 1];
        const da = Math.hypot(p.x - a.x, p.y - a.y) || 1;
        const db = Math.hypot(b.x - p.x, b.y - p.y) || 1;
        const rr = Math.min(r, da / 2, db / 2);
        const p1 = {x: p.x + ((a.x - p.x) / da) * rr, y: p.y + ((a.y - p.y) / da) * rr};
        const p2 = {x: p.x + ((b.x - p.x) / db) * rr, y: p.y + ((b.y - p.y) / db) * rr};
        d += ` L ${p1.x} ${p1.y} Q ${p.x} ${p.y} ${p2.x} ${p2.y}`;
    }
    const last = pts[pts.length - 1];
    d += ` L ${last.x} ${last.y}`;
    return d;
}

// elkjs (~1.4 MB) is lazy-loaded on first layout so it stays out of the initial bundle.
let elkPromise: Promise<ELK> | undefined;
const getElk = (): Promise<ELK> => {
    if (!elkPromise) {
        elkPromise = import("elkjs/lib/elk.bundled.js").then(m => new m.default());
    }
    return elkPromise;
};

function newId(): string {
    return "step-" + Math.random().toString(36).slice(2, 8);
}

/**
 * The step ids that must ALL have completed before this step can start — the plural
 * `preconditionStepIds` when non-empty, else the singular `preconditionStepId`, else none.
 * Mirrors the engine's `Step.preconditions()` so the graph draws exactly the edges the
 * orchestrator honours.
 */
function preconditionsOf(step: WorkflowStep): string[] {
    if (step.preconditionStepIds && step.preconditionStepIds.length > 0) {
        return step.preconditionStepIds.filter(Boolean);
    }
    if (step.preconditionStepId) {
        return [step.preconditionStepId];
    }
    return [];
}

/**
 * Every root→sink path through the sequence graph (each a list of step ids), for the
 * path-by-path token animation. Roots are steps with no precondition; sinks are steps nothing
 * depends on. Capped, and cycle-guarded, so a pathological graph can't blow up.
 */
/** Step ids that are some rollbackable step's compensationStepId. */
function compTargets(steps: WorkflowStep[]): Set<string> {
    const t = new Set<string>();
    for (const s of steps) if (s.rollbackable && s.compensationStepId) t.add(s.compensationStepId);
    return t;
}

function allPaths(steps: WorkflowStep[]): string[][] {
    const ids = new Set(steps.map(s => s.id));
    const targets = compTargets(steps);
    const outgoing: Record<string, string[]> = {};
    const hasIncoming = new Set<string>();
    for (const s of steps) {
        // Normal sequence edges — but not the false-guarded anchor that keeps a compensation
        // step valid at load: a compensation step is only entered through its compensation edge.
        if (!targets.has(s.id)) {
            for (const from of preconditionsOf(s)) {
                if (!ids.has(from)) continue;
                (outgoing[from] ??= []).push(s.id);
                hasIncoming.add(s.id);
            }
        }
        // Compensation edge — the error case: a rollbackable step can go to its compensation.
        if (s.rollbackable && s.compensationStepId && ids.has(s.compensationStepId)) {
            (outgoing[s.id] ??= []).push(s.compensationStepId);
            hasIncoming.add(s.compensationStepId);
        }
    }
    const roots = steps.map(s => s.id).filter(id => !hasIncoming.has(id));
    const paths: string[][] = [];
    const MAX = 200;
    const dfs = (node: string, trail: string[], seen: Set<string>) => {
        if (paths.length >= MAX) return;
        trail.push(node);
        seen.add(node);
        const outs = (outgoing[node] ?? []).filter(n => !seen.has(n));
        if (outs.length === 0) {
            paths.push([...trail]);
        } else {
            for (const nxt of outs) dfs(nxt, trail, seen);
        }
        trail.pop();
        seen.delete(node);
    };
    for (const r of roots) dfs(r, [], new Set());
    return paths;
}

// ── Component ─────────────────────────────────────────────────────────────────

@customElement("eventconductor-workflow-graph")
export class MateuWorkflowElk extends LitElement {

    /** JSON string of the WorkflowDefinition. */
    @property() value = '{"name":"New Workflow","steps":[]}';

    /** When true, all editing interactions are disabled. */
    @property({type: Boolean}) readOnly = false;

    /** Reflected so `:host([dark])` maps the theme onto the host's Lumo dark palette. */
    @property({type: Boolean, reflect: true}) dark = false;

    @state() private wf: WorkflowDefinition = {name: "New Workflow", steps: []};
    @state() private positions: Record<string, NodePos> = {};
    @state() private layoutReady = false;
    @state() private selectedId: string | null = null;
    @state() private showMeta = false;
    @state() private layoutError: string | null = null;
    /** When true, the graph overlays the whole viewport (expand button). */
    @state() private fullscreen = false;
    /** When true, animated tokens flow along the sequence edges (BPMN token simulation). */
    @state() private flowOn = true;

    // ── Token-flow animation state (driven by requestAnimationFrame, off the render path) ──
    private flowRaf = 0;
    private flowStartTs = 0;
    /** All root→sink paths; one is animated at a time, cycling. */
    private flowPaths: string[][] = [];
    private flowPathIndex = 0;
    /** nodes already pinged on the current path pass (so each pings once per pass). */
    private pulsedThisPath = new Set<string>();
    /** nodeId → timestamp of the last token arrival, for the ping effect. */
    private pulseAt: Record<string, number> = {};
    /** nodeId → ping colour ("" = default/primary, red on an error/compensation path). */
    private pulseColor: Record<string, string> = {};

    // ── Focus interaction ───────────────────────────────────────────────────────
    /**
     * 'auto' cycles every path; 'reachable' (shift+click a node) keeps that node's
     * ancestors + descendants and dims the rest; 'path' (alt+click) shows a single path through
     * the node and cycles to the next on each further alt+click.
     */
    private focusMode: "auto" | "reachable" | "path" = "auto";
    private focusNodeId: string | null = null;
    /** The paths currently animated — all of them, or the ones passing through the focus node. */
    private activePaths: string[][] = [];

    /** Distributed edge routes ("from->to" → polyline), set by renderEdges and reused by the
     * token (pathGeometry) and guard chips so they follow the exact painted lines. */
    private edgeCache = new Map<string, Pt[]>();

    private draggingId: string | null = null;
    private dragOffset = {x: 0, y: 0};
    private svgEl: SVGSVGElement | null = null;
    /** Track which step ids already have an ELK-computed position so we only
     *  re-layout genuinely new nodes, not ones the user has repositioned. */
    private elkPositioned = new Set<string>();

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    updated(changed: Map<string, unknown>) {
        if (changed.has("value")) {
            try {
                const parsed: WorkflowDefinition = JSON.parse(this.value);
                // Detect if the step list changed so we re-run ELK
                const oldIds = new Set((this.wf.steps ?? []).map(s => s.id));
                const newIds = new Set((parsed.steps ?? []).map(s => s.id));
                const structureChanged =
                    oldIds.size !== newIds.size ||
                    [...newIds].some(id => !oldIds.has(id)) ||
                    [...newIds].some(id => {
                        const oldStep = (this.wf.steps ?? []).find(s => s.id === id);
                        const newStep = (parsed.steps ?? []).find(s => s.id === id);
                        return oldStep && newStep &&
                            preconditionsOf(oldStep).join(",") !== preconditionsOf(newStep).join(",");
                    });
                this.wf = parsed;
                if (structureChanged || !this.layoutReady) {
                    this.runElkLayout();
                }
                // Recompute the paths the token animation cycles through; reset focus.
                this.flowPaths = allPaths(this.wf.steps ?? []);
                this.focusMode = "auto";
                this.focusNodeId = null;
                this.activePaths = this.flowPaths;
                this.flowPathIndex = 0;
                this.pulsedThisPath = new Set();
            } catch {
                /* keep previous */
            }
        }
        // Keep the token-flow loop in sync with the toggle and layout readiness.
        if (this.flowOn && this.layoutReady) this.startFlow();
        else this.stopFlow();
    }

    disconnectedCallback() {
        super.disconnectedCallback();
        this.stopFlow();
    }

    // ── ELK layout ────────────────────────────────────────────────────────────

    private async runElkLayout() {
        const steps = this.wf.steps ?? [];
        if (steps.length === 0) {
            this.positions = {};
            this.layoutReady = true;
            return;
        }

        const graph: ElkNode = {
            id: "root",
            layoutOptions: {
                "elk.algorithm": "layered",
                "elk.direction": "RIGHT",
                "elk.spacing.nodeNode": "45",
                "elk.layered.spacing.nodeNodeBetweenLayers": "90",
                "elk.layered.spacing.edgeNodeBetweenLayers": "25",
                "elk.spacing.edgeNode": "18",
                "elk.spacing.edgeEdge": "12",
                "elk.edgeRouting": "ORTHOGONAL",
                "elk.layered.nodePlacement.strategy": "BRANDES_KOEPF",
            },
            children: steps.map(s => {
                const {w, h} = sizeOf(s.type);
                return {id: s.id, width: w, height: h};
            }),
            // One edge per precondition: a step with several incoming preconditions
            // (preconditionStepIds) gets several edges into it.
            edges: steps.flatMap(s =>
                preconditionsOf(s).map(from => ({
                    id: `${from}->${s.id}`,
                    sources: [from],
                    targets: [s.id],
                } as ElkExtendedEdge))),
        };

        try {
            const elk = await getElk();
            const laid = await elk.layout(graph);
            const newPositions: Record<string, NodePos> = {...this.positions};
            for (const child of laid.children ?? []) {
                // Only override position for nodes not manually repositioned
                if (!this.elkPositioned.has(child.id) || !newPositions[child.id]) {
                    newPositions[child.id] = {
                        x: (child.x ?? 0) + PAD,
                        y: (child.y ?? 0) + PAD,
                    };
                    this.elkPositioned.add(child.id);
                }
            }
            this.positions = newPositions;
            this.layoutReady = true;
            this.layoutError = null;
        } catch (e) {
            this.layoutError = (e as Error)?.message ?? "ELK layout failed";
            this.layoutReady = true;
        }
    }

    // ── Mutation helpers ──────────────────────────────────────────────────────

    private emit() {
        const json = JSON.stringify(this.wf, null, 2);
        this.dispatchEvent(new CustomEvent("value-changed", {detail: {value: json}, bubbles: true, composed: true}));
    }

    private updateWf(patch: Partial<WorkflowDefinition>) {
        this.wf = {...this.wf, ...patch};
        this.emit();
    }

    private updateStep(id: string, patch: Partial<WorkflowStep>) {
        const steps = this.wf.steps.map(s => s.id === id ? {...s, ...patch} : s);
        const oldStep = this.wf.steps.find(s => s.id === id);
        const newStep = steps.find(s => s.id === id);
        const edgeChanged = !!oldStep && !!newStep &&
            preconditionsOf(oldStep).join(",") !== preconditionsOf(newStep).join(",");
        this.wf = {...this.wf, steps};
        if (edgeChanged) {
            // Invalidate ELK positions so a fresh layout runs
            this.elkPositioned.clear();
            this.runElkLayout();
        }
        this.emit();
    }

    /**
     * Adds or removes one incoming precondition. Normalises onto the plural
     * `preconditionStepIds` (and clears the singular `preconditionStepId`) so a step can have
     * any number of inputs; an empty set drops the field entirely.
     */
    private togglePrecondition(step: WorkflowStep, otherId: string, checked: boolean) {
        const current = new Set(preconditionsOf(step));
        if (checked) current.add(otherId); else current.delete(otherId);
        const list = [...current];
        this.updateStep(step.id, {
            preconditionStepIds: list.length ? list : undefined,
            preconditionStepId: undefined,
        });
    }

    private addStep() {
        const id = newId();
        const step: WorkflowStep = {id, type: "ACTION", name: "New Step"};
        this.wf = {...this.wf, steps: [...(this.wf.steps ?? []), step]};
        // Position new step to the right of the rightmost existing node until ELK runs
        const xs = Object.values(this.positions).map(p => p.x);
        this.positions = {
            ...this.positions,
            [id]: {x: xs.length ? Math.max(...xs) + NODE_W + 80 : PAD, y: PAD},
        };
        this.selectedId = id;
        // Re-run ELK for the new node (don't lock old positions so the whole
        // graph gets a clean layout with the new node included)
        this.elkPositioned.clear();
        this.runElkLayout();
        this.emit();
    }

    private deleteStep(id: string) {
        this.wf = {
            ...this.wf,
            steps: this.wf.steps
                .filter(s => s.id !== id)
                .map(s => {
                    const next = {...s};
                    if (next.preconditionStepId === id) next.preconditionStepId = undefined;
                    if (next.preconditionStepIds) {
                        next.preconditionStepIds = next.preconditionStepIds.filter(p => p !== id);
                    }
                    return next;
                }),
        };
        const {[id]: _, ...rest} = this.positions;
        this.positions = rest;
        this.elkPositioned.delete(id);
        if (this.selectedId === id) this.selectedId = null;
        this.runElkLayout();
        this.emit();
    }

    // ── Drag & drop ───────────────────────────────────────────────────────────

    private onNodeMouseDown(e: MouseEvent, id: string) {
        if (this.readOnly) return;
        if (e.shiftKey || e.altKey) return; // shift/alt are focus clicks, not drags
        e.preventDefault();
        this.draggingId = id;
        const pos = this.positions[id] ?? {x: 0, y: 0};
        const pt = this.toSvgPoint(e);
        this.dragOffset = {x: pt.x - pos.x, y: pt.y - pos.y};
        this.svgEl = (e.currentTarget as SVGElement).closest("svg") as SVGSVGElement;
        window.addEventListener("mousemove", this.onMouseMove);
        window.addEventListener("mouseup", this.onMouseUp);
    }

    private onMouseMove = (e: MouseEvent) => {
        if (!this.draggingId || !this.svgEl) return;
        const pt = this.toSvgPoint(e);
        // Mark as manually positioned so ELK won't override it next run
        this.elkPositioned.add(this.draggingId);
        this.positions = {
            ...this.positions,
            [this.draggingId]: {
                x: Math.max(0, pt.x - this.dragOffset.x),
                y: Math.max(0, pt.y - this.dragOffset.y),
            },
        };
    };

    private onMouseUp = () => {
        this.draggingId = null;
        window.removeEventListener("mousemove", this.onMouseMove);
        window.removeEventListener("mouseup", this.onMouseUp);
    };

    private toSvgPoint(e: MouseEvent): {x: number; y: number} {
        if (!this.svgEl) return {x: 0, y: 0};
        const rect = this.svgEl.getBoundingClientRect();
        return {x: e.clientX - rect.left, y: e.clientY - rect.top};
    }

    // ── Re-layout button ──────────────────────────────────────────────────────

    private relayout() {
        this.elkPositioned.clear();
        this.runElkLayout();
    }

    // ── Canvas size ───────────────────────────────────────────────────────────

    private canvasSize() {
        let w = 600, h = 400;
        for (const s of this.wf.steps ?? []) {
            const p = this.positions[s.id];
            if (!p) continue;
            const sz = sizeOf(s.type);
            w = Math.max(w, p.x + sz.w + PAD);
            h = Math.max(h, p.y + sz.h + PAD);
        }
        return {w, h};
    }

    /** Center-plus-size box of a step (by id), honouring its per-type shape size. */
    private boxForId(id: string): Box | null {
        const pos = this.positions[id];
        const step = (this.wf.steps ?? []).find(s => s.id === id);
        if (!pos || !step) return null;
        const {w, h} = sizeOf(step.type);
        return {x: pos.x + w / 2, y: pos.y + h / 2, w, h};
    }

    /** Orthogonal route between two steps that steers around every other node. */
    private routeBetween(fromId: string, toId: string, spread = 0): Pt[] | null {
        const a = this.boxForId(fromId), b = this.boxForId(toId);
        if (!a || !b) return null;
        const obstacles: Box[] = [];
        for (const s of this.wf.steps ?? []) {
            if (s.id === fromId || s.id === toId) continue;
            const box = this.boxForId(s.id);
            if (box) obstacles.push(box);
        }
        return routeAvoiding(a, b, obstacles, spread);
    }

    /**
     * All edges with node-avoiding routes AND distributed endpoints: edges sharing a side of a
     * node attach at distinct points along that side, so parallel lines never overlap. Sequence
     * edges first, compensation last. The routes are also cached (by "from->to") for the token.
     */
    private computeEdges(): {key: string; from: string; to: string; comp: boolean; pts: Pt[]}[] {
        const steps = this.wf.steps ?? [];
        const targets = compTargets(steps);
        const raw: {from: string; to: string; comp: boolean}[] = [];
        for (const s of steps) {
            if (targets.has(s.id)) continue;
            for (const f of preconditionsOf(s)) if (this.boxForId(f) && this.boxForId(s.id)) raw.push({from: f, to: s.id, comp: false});
        }
        for (const s of steps) {
            if (s.rollbackable && s.compensationStepId && this.boxForId(s.id) && this.boxForId(s.compensationStepId)) {
                raw.push({from: s.id, to: s.compensationStepId, comp: true});
            }
        }

        const sideOf = (b: Box, px: number, py: number): Side => {
            const dx = px - b.x, dy = py - b.y;
            return Math.abs(dx) >= Math.abs(dy) ? (dx >= 0 ? "R" : "L") : (dy >= 0 ? "B" : "T");
        };
        const sides: [Side, Side][] = raw.map(e => {
            const A = this.boxForId(e.from)!, B = this.boxForId(e.to)!;
            return [sideOf(A, B.x, B.y), sideOf(B, A.x, A.y)];
        });

        // Group the endpoints landing on each (node, side) so they can be spread along it.
        const groups = new Map<string, {edge: number; role: 0 | 1; perp: number}[]>();
        raw.forEach((e, idx) => {
            const A = this.boxForId(e.from)!, B = this.boxForId(e.to)!;
            const [sS, tS] = sides[idx];
            const g1 = `${e.from}|${sS}`, g2 = `${e.to}|${tS}`;
            (groups.get(g1) ?? groups.set(g1, []).get(g1)!).push({edge: idx, role: 0, perp: (sS === "L" || sS === "R") ? B.y : B.x});
            (groups.get(g2) ?? groups.set(g2, []).get(g2)!).push({edge: idx, role: 1, perp: (tS === "L" || tS === "R") ? A.y : A.x});
        });
        const attach: [Pt, Pt][] = raw.map(() => [{x: 0, y: 0}, {x: 0, y: 0}]);
        for (const [k, members] of groups) {
            const sd = k.slice(k.lastIndexOf("|") + 1) as Side;
            const box = this.boxForId(k.slice(0, k.lastIndexOf("|")))!;
            members.sort((a, b) => a.perp - b.perp);
            const n = members.length;
            members.forEach((m, i) => {
                const f = n <= 1 ? 0.5 : 0.28 + 0.44 * (i / (n - 1)); // spread across the middle of the side
                const pt: Pt = sd === "R" ? {x: box.x + box.w / 2, y: box.y - box.h / 2 + box.h * f}
                    : sd === "L" ? {x: box.x - box.w / 2, y: box.y - box.h / 2 + box.h * f}
                    : sd === "T" ? {x: box.x - box.w / 2 + box.w * f, y: box.y - box.h / 2}
                    : {x: box.x - box.w / 2 + box.w * f, y: box.y + box.h / 2};
                attach[m.edge][m.role] = pt;
            });
        }

        return raw.map((e, idx) => {
            const [sS, tS] = sides[idx];
            const obstacles: Box[] = [];
            for (const s of steps) {
                if (s.id === e.from || s.id === e.to) continue;
                const box = this.boxForId(s.id);
                if (box) obstacles.push(box);
            }
            return {key: `${e.from}->${e.to}`, from: e.from, to: e.to, comp: e.comp,
                pts: routeThrough(attach[idx][0], sS, attach[idx][1], tS, obstacles)};
        });
    }

    // ── Token-flow animation (path by path) ─────────────────────────────────────

    /**
     * The polyline a token walks for a path (list of step ids): the edge routes joined end to
     * end. The token stays ON the edges — while it crosses a node it is hidden (`hidden` ranges),
     * since the node's own ping already marks the passage. `marks` gives the distance at which
     * the token reaches each node (for the ping).
     */
    private pathGeometry(ids: string[]): {pts: Pt[]; marks: {id: string; d: number}[]; hidden: {from: number; to: number}[]} | null {
        const boxes = ids.map(id => this.boxForId(id));
        if (boxes.some(b => !b)) return null;
        if (ids.length < 2) {
            return {pts: [{x: boxes[0]!.x, y: boxes[0]!.y}], marks: [{id: ids[0], d: 0}], hidden: []};
        }
        const edges: Pt[][] = [];
        for (let i = 1; i < ids.length; i++) {
            // Reuse the exact distributed route the edge was drawn with, so the token walks the
            // painted line (not a re-derived box-center one that could diverge / overlap).
            const e = this.edgeCache.get(`${ids[i - 1]}->${ids[i]}`) ?? this.routeBetween(ids[i - 1], ids[i], 0);
            if (!e) return null;
            edges.push(e);
        }

        const pts: Pt[] = [...edges[0]];
        const marks = [{id: ids[0], d: 0}];
        const hidden: {from: number; to: number}[] = [];
        for (let j = 1; j < edges.length; j++) {
            const d0 = polylineLength(pts);   // at node j's entry border (end of the previous edge)
            pts.push(edges[j][0]);            // node j's exit border (start of this edge)
            const d1 = polylineLength(pts);
            hidden.push({from: d0, to: d1});  // straight span across node j → token hidden here
            marks.push({id: ids[j], d: d0});  // ping node j as the token reaches it
            pts.push(...edges[j].slice(1));
        }
        marks.push({id: ids[ids.length - 1], d: polylineLength(pts)}); // sink node arrival
        return {pts, marks, hidden};
    }

    private onNodeClick(e: MouseEvent, id: string) {
        e.stopPropagation();
        if (e.shiftKey) { this.focusReachable(id); return; }   // node's ancestors + descendants
        if (e.altKey) { this.focusNextPath(id); return; }      // one path through the node, cycling
        this.clearFocus();
        this.selectedId = id;
    }

    /** Root→sink paths passing through a node (falls back to all paths if none). */
    private pathsThrough(id: string): string[][] {
        const through = this.flowPaths.filter(p => p.includes(id));
        return through.length ? through : this.flowPaths;
    }

    private focusReachable(id: string) {
        this.focusMode = "reachable";
        this.focusNodeId = id;
        this.activePaths = this.pathsThrough(id);
        this.restartFlow();
        this.flowOn = true;
    }

    private focusNextPath(id: string) {
        const through = this.pathsThrough(id);
        if (this.focusMode === "path" && this.focusNodeId === id) {
            this.flowPathIndex = (this.flowPathIndex + 1) % through.length; // next path through it
        } else {
            this.focusMode = "path";
            this.focusNodeId = id;
            this.flowPathIndex = 0;
        }
        this.activePaths = through;
        this.flowStartTs = performance.now(); // restart the token from this path's beginning
        this.pulsedThisPath = new Set();
        this.flowOn = true;
    }

    private clearFocus() {
        this.focusMode = "auto";
        this.focusNodeId = null;
        this.activePaths = this.flowPaths;
        this.restartFlow();
    }

    /** Restart the animation at the first active path (used when the focus set changes). */
    private restartFlow() {
        this.flowPathIndex = 0;
        this.flowStartTs = performance.now();
        this.pulsedThisPath = new Set();
    }

    private startFlow() {
        if (this.flowRaf) return;
        this.flowStartTs = performance.now();
        this.pulsedThisPath = new Set();
        const tick = (now: number) => {
            if (!this.flowOn) { this.flowRaf = 0; return; }
            this.stepFlow(now);
            this.flowRaf = requestAnimationFrame(tick);
        };
        this.flowRaf = requestAnimationFrame(tick);
    }

    private stopFlow() {
        if (this.flowRaf) cancelAnimationFrame(this.flowRaf);
        this.flowRaf = 0;
        this.pulseAt = {};
        this.pulseColor = {};
        this.pulsedThisPath = new Set();
        const root = this.renderRoot as unknown as ParentNode;
        root.querySelectorAll?.("[data-pulse]").forEach(el => (el as SVGElement).setAttribute("opacity", "0"));
        root.querySelectorAll?.("[data-edge]").forEach(el => el.classList.remove("dim", "active"));
        root.querySelectorAll?.(".node").forEach(el => el.classList.remove("dim"));
        const token = root.querySelector?.(".flow-token") as SVGElement | null;
        if (token) { token.style.opacity = "0"; token.style.fill = ""; }
    }

    /**
     * One animation frame: a single token walks the current path from its root to its sink; the
     * other edges dim, each node pings as the token reaches it, and when the path finishes the
     * next path takes over (looping). Runs off the Lit render path via direct SVG mutation.
     */
    private stepFlow(now: number) {
        const root = this.renderRoot as unknown as ParentNode;
        const token = root.querySelector?.(".flow-token") as SVGCircleElement | null;
        const paths = this.activePaths.length ? this.activePaths : this.flowPaths;
        if (!token || paths.length === 0) { if (token) token.style.opacity = "0"; return; }

        const idx = this.flowPathIndex % paths.length;
        const path = paths[idx];
        const geo = this.pathGeometry(path);
        if (!geo) return;
        const len = polylineLength(geo.pts) || 1;
        const speed = 180;   // px per second
        const pausePx = 55;  // brief gap between paths
        const dist = ((now - this.flowStartTs) / 1000) * speed;

        if (dist >= len + pausePx) {
            // In 'path' focus we loop the chosen path; otherwise advance to the next one.
            if (this.focusMode !== "path") this.flowPathIndex = (idx + 1) % paths.length;
            this.flowStartTs = now;
            this.pulsedThisPath = new Set();
            return;
        }

        // Position the token; hide it while it crosses a node (the ping marks that) and during
        // the brief inter-path pause.
        const clamped = Math.min(dist, len);
        const p = polylinePointAt(geo.pts, clamped / len);
        token.setAttribute("cx", String(p.x));
        token.setAttribute("cy", String(p.y));
        const crossingNode = geo.hidden.some(hr => clamped >= hr.from && clamped <= hr.to);
        token.style.opacity = (dist <= len && !crossingNode) ? "1" : "0";

        // On an error/compensation path (its last edge is a compensation edge), only the failing
        // rollbackable node pings red to flag the failure. The compensation step is the
        // (successful) recovery, so it — and the token — keep their normal colour.
        const byId = new Map((this.wf.steps ?? []).map(s => [s.id, s] as const));
        const errorNodes = new Set<string>();
        for (let i = 1; i < path.length; i++) {
            const s = byId.get(path[i - 1]);
            if (s && s.rollbackable && s.compensationStepId === path[i]) errorNodes.add(path[i - 1]);
        }

        // Ping each node once, as the token reaches it (red only on the failing node).
        for (const m of geo.marks) {
            if (dist >= m.d && !this.pulsedThisPath.has(m.id)) {
                this.pulseAt[m.id] = now;
                this.pulseColor[m.id] = errorNodes.has(m.id) ? "#dc2626" : "";
                this.pulsedThisPath.add(m.id);
            }
        }

        // The currently-animated path's edges (brightest) and, in a focus mode, the "universe"
        // to keep un-dimmed (the reachable sub-graph, or just the chosen path). In auto mode
        // there is no universe, so everything but the animated path dims.
        const activeEdges = new Set<string>();
        for (let i = 1; i < path.length; i++) activeEdges.add(`${path[i - 1]}->${path[i]}`);
        let unionEdges: Set<string> | null = null;
        let focusNodes: Set<string> | null = null;
        if (this.focusMode !== "auto") {
            unionEdges = new Set();
            focusNodes = new Set();
            const universe = this.focusMode === "path" ? [path] : paths;
            for (const pth of universe) {
                for (let i = 0; i < pth.length; i++) {
                    focusNodes.add(pth[i]);
                    if (i > 0) unionEdges.add(`${pth[i - 1]}->${pth[i]}`);
                }
            }
        }

        // Sequence AND compensation edges (both carry data-edge): the animated path is 'active',
        // and anything outside the focus universe (or, in auto mode, off the current path) dims.
        root.querySelectorAll?.("[data-edge]").forEach(el => {
            const key = (el as SVGElement).dataset.edge ?? "";
            const isActive = activeEdges.has(key);
            const dim = unionEdges ? (!unionEdges.has(key) && !isActive) : !isActive;
            el.classList.toggle("active", isActive);
            el.classList.toggle("dim", dim);
        });

        // Dim nodes outside the focus universe, and render the node pings.
        for (const s of this.wf.steps ?? []) {
            const g = root.querySelector?.(`.node[data-node="${s.id}"]`) as SVGGElement | null;
            if (g) g.classList.toggle("dim", !!focusNodes && !focusNodes.has(s.id));
            const ring = root.querySelector?.(`[data-pulse="${s.id}"]`) as SVGCircleElement | null;
            if (!ring) continue;
            const t0 = this.pulseAt[s.id];
            const dt = t0 ? (now - t0) / 1000 : Infinity;
            // A failing node trembles briefly while its red ping is fresh (CSS @keyframes ec-shake).
            if (g) g.classList.toggle("err", errorNodes.has(s.id) && dt < 0.45);
            if (dt > 0.6) { ring.setAttribute("opacity", "0"); continue; }
            const k = dt / 0.6;
            const base = Math.max(sizeOf(s.type).w, sizeOf(s.type).h) / 2;
            ring.style.fill = this.pulseColor[s.id] || "";  // red on error nodes, else default
            ring.setAttribute("r", String(base + k * 16));
            ring.setAttribute("opacity", String((1 - k) * 0.45));
        }
    }

    // ── Render ────────────────────────────────────────────────────────────────

    render() {
        if (!this.layoutReady) {
            return html`<div class="loading">Computing layout…</div>`;
        }

        const {w, h} = this.canvasSize();
        const steps = this.wf.steps ?? [];

        return html`
            <div class="root ${this.fullscreen ? "fullscreen" : ""}">
                <button class="flow-btn" title="${this.flowOn ? "Pause token flow" : "Play token flow"}"
                        @click="${() => { this.flowOn = !this.flowOn; }}">
                    ${this.flowOn ? "⏸" : "▶"}
                </button>
                <button class="expand-btn" title="${this.fullscreen ? "Collapse" : "Expand"}"
                        @click="${() => { this.fullscreen = !this.fullscreen; }}">
                    ${this.fullscreen ? "✕" : "⤢"}
                </button>
                ${this.readOnly ? nothing : this.renderToolbar()}
                ${this.showMeta ? this.renderMeta() : ""}
                ${this.layoutError ? html`<div class="error">⚠ ${this.layoutError}</div>` : ""}
                <div class="workspace">
                    <div class="canvas-wrap">
                        <svg width="${w}" height="${h}" class="canvas"
                             @click="${(e: MouseEvent) => {if (e.target === e.currentTarget) { this.selectedId = null; this.clearFocus(); }}}">
                            <defs>
                                <marker id="ec-arrow" markerWidth="9" markerHeight="9"
                                        refX="7.5" refY="3.2" orient="auto" markerUnits="userSpaceOnUse">
                                    <path d="M0,0 L0,6.4 L8,3.2 z" fill="context-stroke"/>
                                </marker>
                                <filter id="ec-shadow" x="-20%" y="-20%" width="140%" height="150%">
                                    <feDropShadow dx="0" dy="1" stdDeviation="1.2" flood-color="#0f172a"
                                                  flood-opacity="0.10"/>
                                </filter>
                            </defs>
                            ${this.renderEdges()}
                            ${steps.map(s => this.renderNode(s))}
                            ${steps.map(s => this.renderGuard(s))}
                            ${this.flowOn ? svg`<circle class="flow-token" r="5.5" cx="-100" cy="-100"/>` : nothing}
                        </svg>
                    </div>
                    ${this.selectedId && !this.readOnly ? this.renderPanel() : ""}
                </div>
            </div>
        `;
    }

    private renderToolbar() {
        const status = this.wf.status ?? "DRAFT";
        return html`
            <div class="toolbar">
                <span class="wf-name">${this.wf.name}</span>
                <span class="badge badge-${status.toLowerCase()}">${status}</span>
                <div style="flex:1"></div>
                <button class="nbtn" title="Re-run ELK layout"
                        @click="${() => this.relayout()}">
                    ${iconSitemap}
                    Re-layout
                </button>
                ${!this.readOnly ? html`
                    <button class="nbtn" @click="${() => this.showMeta = !this.showMeta}">
                        ${iconCog}
                        Settings
                    </button>
                    <button class="nbtn primary" @click="${() => this.addStep()}">
                        ${iconPlus}
                        Add Step
                    </button>
                ` : nothing}
                <button class="nbtn" @click="${() => this.exportJson()}">
                    ${iconDownload}
                    Export
                </button>
            </div>
        `;
    }

    private renderMeta() {
        const wf = this.wf;
        return html`
            <div class="meta-panel">
                <div class="meta-grid">
                    <label>Name</label>
                    <input class="inp" .value="${wf.name}"
                           @change="${(e: Event) => this.updateWf({name: (e.target as HTMLInputElement).value})}"/>
                    <label>Description</label>
                    <textarea class="inp" rows="2"
                              @change="${(e: Event) => this.updateWf({description: (e.target as HTMLTextAreaElement).value})}">${wf.description ?? ""}</textarea>
                    <label>Status</label>
                    <select class="inp"
                            @change="${(e: Event) => this.updateWf({status: (e.target as HTMLSelectElement).value as WorkflowStatus})}">
                        ${(["DRAFT", "ACTIVE", "DISABLED", "ARCHIVED"] as WorkflowStatus[]).map(s => html`
                            <option value="${s}" ?selected="${wf.status === s}">${s}</option>`)}
                    </select>
                    <label>Limit concurrent</label>
                    <input type="checkbox" ?checked="${wf.limitConcurrentExecutions}"
                           @change="${(e: Event) => this.updateWf({limitConcurrentExecutions: (e.target as HTMLInputElement).checked})}"/>
                    ${wf.limitConcurrentExecutions ? html`
                        <label>Max concurrent</label>
                        <input class="inp" type="number" min="0"
                               .value="${String(wf.maxConcurrentExecutions ?? 0)}"
                               @change="${(e: Event) => this.updateWf({maxConcurrentExecutions: Number((e.target as HTMLInputElement).value)})}"/>
                        <label>Enqueue on limit</label>
                        <input type="checkbox" ?checked="${wf.enqueueOnLimit}"
                               @change="${(e: Event) => this.updateWf({enqueueOnLimit: (e.target as HTMLInputElement).checked})}"/>
                    ` : ""}
                </div>
            </div>
        `;
    }

    /**
     * All edges (sequence + compensation) drawn in one pass, so a later line can bridge (hop
     * over with a small arc) any earlier line it crosses — the classic wiring-diagram look, as
     * in modux. Compensation edges are drawn last so they hop over the sequence flow.
     */
    private renderEdges() {
        const edges = this.computeEdges();
        this.edgeCache = new Map(edges.map(e => [e.key, e.pts])); // token & guards reuse these routes
        const prior: [Pt, Pt][] = [];
        const out: unknown[] = [];
        for (const e of edges) {
            const d = bridgedPath(e.pts, prior);
            out.push(e.comp
                ? svg`<path class="comp-edge" data-comp="${e.from}" data-edge="${e.key}"
                             d="${d}" marker-end="url(#ec-arrow)"/>`
                : svg`<path class="edge" data-edge="${e.key}"
                             d="${d}" marker-end="url(#ec-arrow)"/>`);
            for (let i = 0; i < e.pts.length - 1; i++) prior.push([e.pts[i], e.pts[i + 1]]);
        }
        return out;
    }

    /**
     * The step's precondition guard (JEXL) painted as a chip on its incoming edge — the guard
     * gates entry to the step, so it is shown once, at the midpoint of the first precondition's
     * route (not per-edge, which would duplicate it).
     */
    private renderGuard(step: WorkflowStep) {
        const expr = step.preconditionExpression?.trim();
        if (!expr) return svg``;
        const to = this.positions[step.id];
        const preconditions = preconditionsOf(step);
        if (!to || preconditions.length === 0) return svg``;
        const route = this.edgeCache.get(`${preconditions[0]}->${step.id}`) ?? this.routeBetween(preconditions[0], step.id, 0);
        if (!route) return svg``;

        // Sit toward the source end of the edge, clear of the target node's badge.
        const mid = polylinePointAt(route, 0.38);
        const text = expr.length > 30 ? expr.slice(0, 29) + "…" : expr;
        const w = Math.max(30, text.length * 6.3 + 22);
        const h = 19;
        return svg`
            <g class="guard" data-edge="${preconditions[0]}->${step.id}" transform="translate(${mid.x}, ${mid.y})">
                <rect class="guard-halo" x="${-w / 2 - 4}" y="${-h / 2 - 4}" width="${w + 8}" height="${h + 8}" rx="12"/>
                <g class="guard-chip">
                    <rect x="${-w / 2}" y="${-h / 2}" width="${w}" height="${h}" rx="9.5"/>
                    <text x="0" y="3.6" text-anchor="middle">◇ ${text}</text>
                </g>
            </g>
        `;
    }

    private renderNode(step: WorkflowStep) {
        const pos = this.positions[step.id] ?? {x: PAD, y: PAD};
        const st = styleOf(step.type);
        const {w, h} = sizeOf(step.type);
        const sel = this.selectedId === step.id ? "sel" : "";
        const label = step.name.length > 22 ? step.name.slice(0, 21) + "…" : step.name;

        // A radar-ping ring that flashes when a flow token arrives (driven by the rAF loop).
        const pulse = svg`<circle class="flow-pulse" data-pulse="${step.id}"
                                  cx="${w / 2}" cy="${h / 2}" r="${Math.max(w, h) / 2}" opacity="0"/>`;

        let shape = svg``;
        if (isEventType(step.type)) {
            // BPMN event: circle (START thin green, END thick red), name below.
            const kind = step.type === "END" ? "ev-end" : "ev-start";
            shape = svg`
                <circle class="node-shape ${kind}" cx="${w / 2}" cy="${h / 2}" r="${w / 2 - 3}"
                        fill="${st.fill}" stroke="${st.stroke}"/>
                <text class="node-caption" x="${w / 2}" y="${h + 15}" text-anchor="middle">${label}</text>`;
        } else if (isGatewayType(step.type)) {
            // BPMN parallel gateway: diamond with a "+", name below.
            const cx = w / 2, cy = h / 2;
            const pts = `${cx},2 ${w - 2},${cy} ${cx},${h - 2} 2,${cy}`;
            shape = svg`
                <polygon class="node-shape gateway" points="${pts}" fill="${st.fill}" stroke="${st.stroke}"/>
                <path class="gw-plus" d="M${cx - 9},${cy} H${cx + 9} M${cx},${cy - 9} V${cy + 9}"
                      stroke="${st.stroke}"/>
                <text class="node-caption" x="${w / 2}" y="${h + 15}" text-anchor="middle">${label}</text>`;
        } else {
            // BPMN task: rounded card with a corner glyph, an uppercase caption, title + id.
            const badge = badgeOf(step);
            const badgeText = badge.length > 26 ? badge.slice(0, 25) + "…" : badge;
            shape = svg`
                <text class="node-badge" x="2" y="-7">${badgeText}</text>
                <rect class="node-shape" width="${w}" height="${h}" rx="10"
                      fill="${st.fill}" stroke="${st.stroke}" stroke-width="1.4"
                      stroke-dasharray="${st.dashed ? "6 4" : "0"}"/>
                <g class="node-symbol" transform="translate(${w - 23}, 9)"
                   fill="none" stroke="${st.stroke}" stroke-width="1.1"
                   stroke-linejoin="round">${SYMBOLS[st.symbol] ?? svg``}</g>
                <text class="node-title" x="14" y="${h / 2 - 2}">${label}</text>
                <text class="node-id" x="14" y="${h / 2 + 14}">${step.id}</text>`;
        }

        return svg`
            <g class="node ${sel}" data-node="${step.id}" transform="translate(${pos.x},${pos.y})"
               @mousedown="${(e: MouseEvent) => this.onNodeMouseDown(e, step.id)}"
               @click="${(e: MouseEvent) => this.onNodeClick(e, step.id)}">
                ${pulse}
                <g class="node-inner" data-inner="${step.id}">${shape}</g>
            </g>
        `;
    }

    private renderPanel() {
        const step = this.wf.steps.find(s => s.id === this.selectedId);
        if (!step) return "";
        const others = this.wf.steps.filter(s => s.id !== step.id);
        const ro = this.readOnly;

        const field = (label: string, body: unknown) => html`
            <div class="field">
                <label class="field-label">${label}</label>
                ${body}
            </div>
        `;

        return html`
            <div class="properties">
                <div class="prop-header">
                    <span>Step Properties</span>
                    ${!ro ? html`<button class="del-btn" title="Delete step"
                            @click="${() => this.deleteStep(step.id)}">🗑</button>` : nothing}
                    <button class="close-btn"
                            @click="${() => this.selectedId = null}">✕</button>
                </div>
                <div class="prop-body">
                    ${field("ID", html`<input class="inp" readonly .value="${step.id}"/>`)}
                    ${field("Name", html`<input class="inp" ?readonly="${ro}" .value="${step.name}"
                        @change="${ro ? nothing : (e: Event) => this.updateStep(step.id, {name: (e.target as HTMLInputElement).value})}"/>`)}
                    ${field("Type", html`
                        <select class="inp" ?disabled="${ro}"
                                @change="${ro ? nothing : (e: Event) => this.updateStep(step.id, {type: (e.target as HTMLSelectElement).value as StepType})}">
                            ${STEP_TYPES.map(t => html`
                                <option value="${t}" ?selected="${step.type === t}">${t}</option>`)}
                        </select>`)}
                    ${field("Description", html`<textarea class="inp" rows="2" ?readonly="${ro}"
                        @change="${ro ? nothing : (e: Event) => this.updateStep(step.id, {description: (e.target as HTMLTextAreaElement).value})}">${step.description ?? ""}</textarea>`)}
                    ${field("Preconditions (all must complete)", html`
                        <div class="checklist">
                            ${others.length === 0 ? html`<span class="check-empty">no other steps</span>`
                                : others.map(s => html`
                                <label class="check">
                                    <input type="checkbox" ?disabled="${ro}"
                                           ?checked="${preconditionsOf(step).includes(s.id)}"
                                           @change="${ro ? nothing : (e: Event) => this.togglePrecondition(step, s.id, (e.target as HTMLInputElement).checked)}"/>
                                    <span>${s.name} <em>(${s.id})</em></span>
                                </label>`)}
                        </div>`)}
                    ${field("Precondition expression", html`
                        <input class="inp" placeholder="JEXL expression" ?readonly="${ro}"
                               .value="${step.preconditionExpression ?? ""}"
                               @change="${ro ? nothing : (e: Event) => this.updateStep(step.id, {preconditionExpression: (e.target as HTMLInputElement).value || undefined})}"/>`)}
                    ${field("Timeout (ms)", html`
                        <input class="inp" type="number" min="0" ?readonly="${ro}"
                               .value="${String(step.timeout ?? 0)}"
                               @change="${ro ? nothing : (e: Event) => this.updateStep(step.id, {timeout: Number((e.target as HTMLInputElement).value)})}"/>`)}
                    ${field("Retries", html`
                        <input class="inp" type="number" min="0" ?readonly="${ro}"
                               .value="${String(step.retries ?? 0)}"
                               @change="${ro ? nothing : (e: Event) => this.updateStep(step.id, {retries: Number((e.target as HTMLInputElement).value)})}"/>`)}
                    <div class="field row">
                        <label class="field-label">Rollbackable</label>
                        <input type="checkbox" ?checked="${step.rollbackable}" ?disabled="${ro}"
                               @change="${ro ? nothing : (e: Event) => this.updateStep(step.id, {rollbackable: (e.target as HTMLInputElement).checked})}"/>
                    </div>
                    ${step.rollbackable ? field("Compensation step", html`
                        <select class="inp" ?disabled="${ro}"
                                @change="${ro ? nothing : (e: Event) => this.updateStep(step.id, {compensationStepId: (e.target as HTMLSelectElement).value || undefined})}">
                            <option value="">— none —</option>
                            ${others.map(s => html`
                                <option value="${s.id}" ?selected="${step.compensationStepId === s.id}">
                                    ${s.name} (${s.id})
                                </option>`)}
                        </select>`) : ""}
                    ${step.type === "ACTION" ? field("Topic", html`
                        <input class="inp" placeholder="kafka.topic.name" ?readonly="${ro}"
                               .value="${step.topic ?? ""}"
                               @change="${ro ? nothing : (e: Event) => this.updateStep(step.id, {topic: (e.target as HTMLInputElement).value || undefined})}"/>`) : ""}
                    ${step.type === "USER_TASK" ? field("Form ID", html`
                        <input class="inp" ?readonly="${ro}" .value="${step.formId ?? ""}"
                               @change="${ro ? nothing : (e: Event) => this.updateStep(step.id, {formId: (e.target as HTMLInputElement).value || undefined})}"/>`) : ""}
                    ${step.type === "RULE" ? field("Rule ID", html`
                        <input class="inp" ?readonly="${ro}" .value="${step.ruleId ?? ""}"
                               @change="${ro ? nothing : (e: Event) => this.updateStep(step.id, {ruleId: (e.target as HTMLInputElement).value || undefined})}"/>`) : ""}
                    ${step.type === "WAIT_FOR_MESSAGE" || step.type === "SEND_MESSAGE" ? field("Message name", html`
                        <input class="inp" ?readonly="${ro}" .value="${step.messageName ?? ""}"
                               @change="${ro ? nothing : (e: Event) => this.updateStep(step.id, {messageName: (e.target as HTMLInputElement).value || undefined})}"/>`) : ""}
                    ${step.type === "PROCESS" ? field("Child workflow ID", html`
                        <input class="inp" ?readonly="${ro}" .value="${step.childWorkflowDefinitionId ?? ""}"
                               @change="${ro ? nothing : (e: Event) => this.updateStep(step.id, {childWorkflowDefinitionId: (e.target as HTMLInputElement).value || undefined})}"/>`) : ""}
                </div>
            </div>
        `;
    }

    // ── Export ────────────────────────────────────────────────────────────────

    private exportJson() {
        const json = JSON.stringify(this.wf, null, 2);
        const blob = new Blob([json], {type: "application/json"});
        const url = URL.createObjectURL(blob);
        const a = document.createElement("a");
        a.href = url;
        a.download = (this.wf.name ?? "workflow").replace(/\s+/g, "-").toLowerCase() + ".json";
        a.click();
        URL.revokeObjectURL(url);
    }

    // ── Styles ────────────────────────────────────────────────────────────────

    static styles = [neutralButtonStyles, css`
        :host {
            /* Fill the host's container (a Mateu zone, or a sized wrapper); fall back to a
               sensible minimum when the container has no height of its own. */
            display: block; height: 100%; min-height: 230px; box-sizing: border-box;
            font-family: var(--lumo-font-family, sans-serif);
            /* Themeable palette (modux-style). Light defaults; :host([dark]) maps onto Lumo. */
            --ec-canvas-bg: #f8fafc;
            --ec-surface: #ffffff;
            --ec-border: #e2e8f0;
            --ec-text: #1e293b;
            --ec-text-dim: #64748b;
            --ec-text-faint: #94a3b8;
            --ec-edge: #94a3b8;
            --ec-primary: #2563eb;
        }
        :host([dark]) {
            --ec-canvas-bg: var(--lumo-shade-5pct, #16181a);
            --ec-surface: var(--lumo-base-color, #1f2123);
            --ec-border: var(--lumo-contrast-20pct, #3a3d42);
            --ec-text: var(--lumo-body-text-color, #e8e9ea);
            --ec-text-dim: var(--lumo-secondary-text-color, #a8adb4);
            --ec-text-faint: var(--lumo-tertiary-text-color, #7d838b);
            --ec-edge: var(--lumo-tertiary-text-color, #7d838b);
            --ec-primary: var(--lumo-primary-color, #60a5fa);
        }

        .root {display: flex; flex-direction: column; height: 100%; position: relative; background: var(--lumo-base-color, #fff);}

        .root.fullscreen {
            position: fixed; inset: 0; height: 100vh; width: 100vw; z-index: 9999;
            box-shadow: 0 0 0 100vmax rgba(0, 0, 0, .15);
        }

        .expand-btn {
            position: absolute; top: 8px; right: 8px; z-index: 6;
            width: 30px; height: 30px; display: flex; align-items: center; justify-content: center;
            border: 1px solid var(--ec-border); border-radius: 6px;
            background: var(--lumo-base-color, #fff); color: var(--ec-text-dim); cursor: pointer;
            font-size: 15px; line-height: 1; box-shadow: 0 1px 2px #0000000f;
        }
        .expand-btn:hover {background: var(--lumo-contrast-5pct, #f1f5f9);}

        .flow-btn {
            position: absolute; top: 8px; right: 44px; z-index: 6;
            width: 30px; height: 30px; display: flex; align-items: center; justify-content: center;
            border: 1px solid var(--ec-border); border-radius: 6px;
            background: var(--lumo-base-color, #fff); color: var(--ec-text-dim); cursor: pointer;
            font-size: 13px; line-height: 1; box-shadow: 0 1px 2px #0000000f;
        }
        .flow-btn:hover {background: var(--lumo-contrast-5pct, #f1f5f9);}

        .loading {
            display: flex; align-items: center; justify-content: center;
            height: 100%; color: var(--ec-text-faint); font-size: .9rem;
        }
        .error {
            padding: .4rem 1rem; background: #fee2e2; color: #991b1b;
            font-size: .8rem; flex-shrink: 0;
        }

        /* toolbar */
        .toolbar {
            display: flex; align-items: center; gap: .5rem;
            padding: .5rem 1rem; flex-shrink: 0;
            border-bottom: 1px solid var(--lumo-contrast-10pct, #e2e8f0);
        }
        .wf-name {font-weight: 600; font-size: 1rem; color: var(--lumo-body-text-color, #1e293b);}
        .badge {
            font-size: .7rem; font-weight: 600; padding: .15rem .5rem;
            border-radius: 9999px; text-transform: uppercase; letter-spacing: .04em;
        }
        .badge-draft    {background: #e2e8f0; color: #475569;}
        .badge-active   {background: #dcfce7; color: #166534;}
        .badge-disabled {background: #fef9c3; color: #854d0e;}
        .badge-archived {background: #fee2e2; color: #991b1b;}

        /* meta */
        .meta-panel {
            padding: .75rem 1rem; flex-shrink: 0;
            border-bottom: 1px solid var(--lumo-contrast-10pct, #e2e8f0);
            background: var(--lumo-contrast-5pct, #f8fafc);
        }
        .meta-grid {display: grid; grid-template-columns: 120px 1fr; gap: .4rem .75rem; align-items: start;}
        .meta-grid label {font-size: .8rem; color: var(--ec-text-dim); padding-top: .3rem;}

        /* workspace */
        .workspace {display: flex; flex: 1; overflow: hidden;}
        .canvas-wrap {flex: 1; overflow: auto; background: var(--ec-canvas-bg);}
        .canvas {display: block;}

        /* nodes */
        .node {cursor: grab; transition: opacity .2s;}
        .node.dim {opacity: .2;}   /* not reachable from the focused node (shift/alt click) */
        .node-shape {filter: url(#ec-shadow); stroke-width: 1.6; transition: stroke .12s, stroke-width .12s;}
        .node-shape.ev-start {stroke-width: 1.8;}
        .node-shape.ev-end {stroke-width: 3;}
        .node:hover .node-shape, .node.sel .node-shape {stroke: var(--ec-primary) !important; stroke-width: 2.6 !important; stroke-dasharray: 0 !important;}
        .gw-plus {stroke-width: 2.2; stroke-linecap: round; fill: none; pointer-events: none;}
        .node-badge {font-size: 9.5px; fill: var(--ec-text-dim); text-transform: uppercase; letter-spacing: .05em; font-weight: 600;}
        .node-caption {font-size: 11px; font-weight: 600; fill: var(--ec-text);}
        .node-symbol {opacity: .9;}
        .node-title {font-size: 13px; font-weight: 600; fill: var(--ec-text);}
        .node-id {font-size: 9.5px; fill: var(--ec-text-faint);}
        /* radar-ping shown as a flow token passes through a node */
        .flow-pulse {fill: var(--ec-primary); pointer-events: none;}
        /* a failing node trembles while its red ping is fresh */
        .node-inner {transform-box: fill-box; transform-origin: center;}
        .node.err .node-inner {animation: ec-shake .4s ease-in-out;}
        @keyframes ec-shake {
            0%, 100% {transform: translateX(0);}
            15% {transform: translateX(-2.5px) rotate(-1deg);}
            30% {transform: translateX(2.5px) rotate(1deg);}
            45% {transform: translateX(-2px) rotate(-.8deg);}
            60% {transform: translateX(2px) rotate(.8deg);}
            75% {transform: translateX(-1px);}
        }

        /* edges */
        .edge {fill: none; stroke: var(--ec-edge); stroke-width: 1.6; stroke-linejoin: round; transition: opacity .2s, stroke .2s, stroke-width .2s;}
        .edge.dim {opacity: .22;}                                    /* not on the active path */
        .edge.active {stroke: var(--ec-primary); stroke-width: 2.4;} /* the path being animated */
        /* compensation associations (BPMN): red dashed */
        .comp-edge {fill: none; stroke: #dc2626; stroke-width: 1.6; stroke-dasharray: 6 5; stroke-linejoin: round; transition: opacity .2s, stroke-width .2s;}
        .comp-edge.dim {opacity: .18;}
        .comp-edge.active {stroke-width: 2.6;}  /* the error path — stays red, just bolder */
        /* the single animated token walking the current path */
        .flow-token {fill: var(--ec-primary); pointer-events: none; filter: drop-shadow(0 0 3px var(--ec-primary));}

        /* precondition guard chips on edges */
        .guard {pointer-events: none; transition: opacity .2s;}
        .guard.dim {opacity: .15;}   /* its edge is not in the focus */
        .guard-chip {transform-box: fill-box; transform-origin: center; transition: transform .2s;}
        .guard rect {fill: var(--ec-surface); stroke: var(--ec-border); stroke-width: 1; transition: stroke .2s, stroke-width .2s;}
        .guard text {
            font-size: 10.5px; fill: var(--ec-text-dim);
            font-family: var(--lumo-font-family-monospace, ui-monospace, monospace);
            transition: fill .2s;
        }
        /* halo behind the chip: hidden until the token walks this edge, then it glows */
        .guard rect.guard-halo {fill: var(--ec-primary); stroke: none; opacity: 0; filter: blur(5px); transition: opacity .2s;}
        .guard.active rect.guard-halo {opacity: .38;}
        .guard.active .guard-chip {transform: scale(1.22);}
        .guard.active .guard-chip rect {stroke: var(--ec-primary); stroke-width: 1.7;}
        .guard.active .guard-chip text {fill: var(--ec-primary); font-weight: 700;}

        /* properties panel */
        .properties {
            width: 280px; flex-shrink: 0;
            border-left: 1px solid var(--lumo-contrast-10pct, #e2e8f0);
            display: flex; flex-direction: column;
            background: var(--lumo-base-color, #fff);
        }
        .prop-header {
            display: flex; align-items: center; gap: .4rem;
            padding: .6rem .75rem; font-size: .85rem; font-weight: 600;
            border-bottom: 1px solid var(--lumo-contrast-10pct, #e2e8f0);
        }
        .prop-header span {flex: 1;}
        .del-btn, .close-btn {
            background: none; border: none; cursor: pointer;
            font-size: .95rem; padding: .1rem .3rem; border-radius: 4px; line-height: 1;
        }
        .del-btn:hover {background: #fee2e2;}
        .close-btn:hover {background: #f1f5f9;}
        .prop-body {flex: 1; overflow-y: auto; padding: .75rem; display: flex; flex-direction: column; gap: .6rem;}

        /* fields */
        .field {display: flex; flex-direction: column; gap: .2rem;}
        .field.row {flex-direction: row; align-items: center; gap: .5rem;}
        .field-label {font-size: .75rem; color: var(--ec-text-dim); font-weight: 500;}
        .inp {
            width: 100%; box-sizing: border-box;
            padding: .3rem .5rem; border: 1px solid var(--ec-border); border-radius: 6px;
            font-size: .82rem; color: var(--ec-text); background: var(--lumo-base-color, #fff);
            outline: none; font-family: inherit; transition: border-color .15s;
        }
        .inp:focus {border-color: var(--ec-primary);}
        textarea.inp {resize: vertical;}
        input[readonly].inp {background: var(--lumo-contrast-5pct, #f8fafc); color: var(--ec-text-faint);}

        /* precondition checklist */
        .checklist {
            display: flex; flex-direction: column; gap: .15rem;
            max-height: 140px; overflow-y: auto;
            border: 1px solid var(--ec-border); border-radius: 6px; padding: .35rem .5rem;
            background: var(--lumo-base-color, #fff);
        }
        .check {display: flex; align-items: center; gap: .4rem; font-size: .8rem; color: var(--ec-text);}
        .check input {margin: 0;}
        .check em {color: var(--ec-text-faint); font-style: normal;}
        .check-empty {font-size: .78rem; color: var(--ec-text-faint);}
    `];
}

declare global {
    interface HTMLElementTagNameMap {
        "mateu-workflow-elk": MateuWorkflowElk;
    }
}

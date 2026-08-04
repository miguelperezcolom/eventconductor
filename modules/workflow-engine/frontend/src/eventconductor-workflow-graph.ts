import {customElement, property, state} from "lit/decorators.js";
import {css, html, LitElement, nothing, svg} from "lit";
import type {ELK, ElkNode, ElkExtendedEdge} from "elkjs/lib/elk.bundled.js";
import {neutralButtonStyles, iconCog, iconPlus, iconSitemap, iconFit} from "./neutralChrome";

// ── Domain types ─────────────────────────────────────────────────────────────

type StepType =
    | "START" | "ACTION" | "USER_TASK" | "RULE" | "TIMER"
    | "WAIT_FOR_MESSAGE" | "SEND_MESSAGE" | "FORK" | "JOIN" | "PROCESS" | "END";
/** Written by an older version of this editor into a field the engine has no such thing as. */
type LegacyWorkflowStatus = "DRAFT" | "ACTIVE" | "DISABLED" | "ARCHIVED";

interface WorkflowStep {
    id: string;
    type: StepType;
    name: string;
    description?: string;
    preconditionStepId?: string;
    preconditionStepIds?: string[];
    /**
     * The incoming links of this step, each with its own guard. Takes precedence over the two
     * older spellings, which say which steps to wait for and nothing about the routes.
     */
    preconditions?: Precondition[];
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
    /** JOIN only: "AND" (default, wait all) or "XOR" (proceed on any one). */
    joinType?: "AND" | "XOR";
}

/** One incoming link: the step to wait for, and the condition under which arriving by it counts. */
interface Precondition {
    stepId: string;
    expression?: string;
}

interface WorkflowDefinition {
    id?: string;
    name: string;
    version?: number;
    description?: string;
    /**
     * Declares that this workflow is not to run: no new instances, cron included. A floor the
     * runtime cannot lift, which is what lets a definition live in the repository without being
     * live.
     */
    disabled?: boolean;
    /** Declares it retired: as disabled, and hidden from the listing. */
    archived?: boolean;
    /**
     * What this editor used to write instead. The engine has no `status` — the field does not
     * exist in the schema, and a file carrying it does not import at all — so it is read here to
     * show the state such a file was meant to be in, and dropped the moment anything is saved.
     */
    status?: LegacyWorkflowStatus;
    limitConcurrentExecutions?: boolean;
    maxConcurrentExecutions?: number;
    enqueueOnLimit?: boolean;
    steps: WorkflowStep[];
}

type StepState = "PENDING" | "RUNNING" | "COMPLETED" | "ERROR" | "CANCELLED" | "COMPENSATED";
/**
 * Per-step monitoring overlay entry (read-only views): a live process count and/or a state, plus
 * the diagnostic detail the hover shows so an operator can answer "why is it here?" without opening
 * the code — the consolidated reason, the last error, retries, what it awaits, deadlines, the
 * worker and a snapshot of the step's variables.
 */
interface StepOverlay {
    count?: number;
    /**
     * Definition view only: per-day histogram of the step's currently stopped/waiting tasks, index =
     * days ago (`heat[0]` = started today). Drives the heatmap toggle + last-N-days slider entirely
     * client-side — the windowed heat is the sum of buckets `[0, days)`.
     */
    heat?: number[];
    state?: StepState;
    active?: boolean;
    reason?: string;
    error?: string;
    attempt?: number;
    maxRetries?: number;
    awaitingMessage?: string;
    correlationKey?: string;
    deadlineAt?: string;
    startedAt?: string;
    worker?: string;
    variables?: { name: string; value: string }[];
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

/**
 * Move a box-border attach point onto the node's actual outline so lines meet the shape with no
 * gap: a circle for events, a diamond for gateways, the box itself for tasks. `cx,cy` is the node
 * centre; `pt` is the (possibly offset) point on the box side; `side` is the side it exits.
 */
function snapToShape(cx: number, cy: number, type: StepType, pt: Pt, side: Side): Pt {
    const horiz = side === "L" || side === "R", sgn = (side === "R" || side === "B") ? 1 : -1;
    const clamp = (v: number, m: number) => Math.max(-m, Math.min(m, v));
    if (isEventType(type)) {
        const R = EVENT_SIZE / 2 - 3;
        if (horiz) { const dy = clamp(pt.y - cy, R - 1); return {x: cx + sgn * Math.sqrt(R * R - dy * dy), y: cy + dy}; }
        const dx = clamp(pt.x - cx, R - 1); return {x: cx + dx, y: cy + sgn * Math.sqrt(R * R - dx * dx)};
    }
    if (isGatewayType(type)) {
        const hw = EVENT_SIZE / 2 - 2, hh = EVENT_SIZE / 2 - 2; // diamond inscribed in the box
        if (horiz) { const dy = clamp(pt.y - cy, hh - 1); return {x: cx + sgn * hw * (1 - Math.abs(dy) / hh), y: cy + dy}; }
        const dx = clamp(pt.x - cx, hw - 1); return {x: cx + dx, y: cy + sgn * hh * (1 - Math.abs(dx) / hw)};
    }
    return pt; // rectangle: the box border already is the shape
}

function stubOut(pt: Pt, side: Side, d: number): Pt {
    return side === "R" ? {x: pt.x + d, y: pt.y} : side === "L" ? {x: pt.x - d, y: pt.y}
        : side === "T" ? {x: pt.x, y: pt.y - d} : {x: pt.x, y: pt.y + d};
}

/**
 * Orthogonal route between two *specific border points* (each leaving its node perpendicular
 * to its side), avoiding the other nodes. Lets edges attach at distinct points on a node so
 * parallel edges never lie on top of one another. Same candidate-scoring idea as routeAvoiding.
 */
function routeThrough(sPt: Pt, sSide: Side, tPt: Pt, tSide: Side, obstacles: Box[], prior: [Pt, Pt][] = [], margin = 20): Pt[] {
    const STUB = 16;
    const S = stubOut(sPt, sSide, STUB), T = stubOut(tPt, tSide, STUB);
    const crossings = (pts: Pt[]): number => {
        let n = 0;
        for (let i = 0; i < pts.length - 1; i++)
            for (const o of obstacles)
                if (segmentCrossesBox(pts[i], pts[i + 1], {x: o.x, y: o.y, w: o.w + 2 * margin, h: o.h + 2 * margin})) n++;
        return n;
    };
    // Length that this path runs collinear-and-coincident with an already-routed edge (what we
    // must avoid): two verticals at the same x, or two horizontals at the same y, that share span.
    const overlap = (pts: Pt[]): number => {
        let total = 0;
        for (let i = 0; i < pts.length - 1; i++) {
            const a = pts[i], b = pts[i + 1];
            const vert = Math.abs(a.x - b.x) < 1.5, horiz = Math.abs(a.y - b.y) < 1.5;
            if (!vert && !horiz) continue;
            for (const [c, d] of prior) {
                if (vert && Math.abs(c.x - d.x) < 1.5 && Math.abs(c.x - a.x) < 2.5) {
                    total += Math.max(0, Math.min(Math.max(a.y, b.y), Math.max(c.y, d.y)) - Math.max(Math.min(a.y, b.y), Math.min(c.y, d.y)));
                } else if (horiz && Math.abs(c.y - d.y) < 1.5 && Math.abs(c.y - a.y) < 2.5) {
                    total += Math.max(0, Math.min(Math.max(a.x, b.x), Math.max(c.x, d.x)) - Math.max(Math.min(a.x, b.x), Math.min(c.x, d.x)));
                }
            }
        }
        return total;
    };
    const cands: Pt[][] = [[{x: T.x, y: S.y}], [{x: S.x, y: T.y}]];
    for (const f of [0.5, 0.4, 0.6, 0.3, 0.7, 0.2, 0.8, 0.15, 0.85]) {
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
        // crossings dominate, then avoiding overlap with existing lines, then length + bend count.
        const score = crossings(full) * 1e6 + overlap(full) * 2e3 + polylineLength(full) + c.length * 40;
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
/**
 * This step's links, whichever way the definition spelled them. The older two carry no guard,
 * which is what they have always meant.
 */
function linksOf(step: WorkflowStep): Precondition[] {
    if (step.preconditions && step.preconditions.length > 0) {
        return step.preconditions.filter(p => p && p.stepId);
    }
    if (step.preconditionStepIds && step.preconditionStepIds.length > 0) {
        return step.preconditionStepIds.filter(Boolean).map(stepId => ({stepId}));
    }
    if (step.preconditionStepId) {
        return [{stepId: step.preconditionStepId}];
    }
    return [];
}

/** Guard of the link from `fromId` into `step`, if that link declares one. */
function guardOf(step: WorkflowStep, fromId: string): string | undefined {
    const expr = linksOf(step).find(l => l.stepId === fromId)?.expression?.trim();
    return expr ? expr : undefined;
}

function preconditionsOf(step: WorkflowStep): string[] {
    return linksOf(step).map(l => l.stepId);
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
            // A lone node is not a path: nothing flows anywhere, and animating it means a token
            // walking a zero-length line while the simulation waits out its turn. A step that is
            // not wired up yet — one just added in the editor — is exactly this case.
            if (trail.length > 1) paths.push([...trail]);
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

    /**
     * Drops the expand button. Inside an IDE editor pane — the VS Code custom editor, the IntelliJ
     * split editor — the component already fills everything it is allowed to fill, so "expand"
     * either does nothing visible or fights the host's own layout. The app, where the graph sits in
     * a page among other things, leaves it on.
     */
    @property({type: Boolean, attribute: "no-expand"}) noExpand = false;

    /**
     * JSON string with a per-step monitoring overlay (read-only views). Map of stepId →
     * `{count?, state?, active?}`:
     *  - `count`: how many process instances currently sit at this step (definition view badge).
     *  - `state`: this step's status in one process (process view): PENDING | RUNNING | COMPLETED
     *    | ERROR | CANCELLED | COMPENSATED.
     *  - `active`: highlight this node as "where the process is now" (process view).
     * Reused as-is by the IDE plugins.
     */
    @property() overlay = "";

    /** Reflected so `:host([dark])` maps the theme onto the host's Lumo dark palette. */
    @property({type: Boolean, reflect: true}) dark = false;

    @state() private wf: WorkflowDefinition = {name: "New Workflow", steps: []};
    /** Parsed monitoring overlay (see the `overlay` property): stepId → live count / state. */
    @state() private overlayData: Record<string, StepOverlay> = {};
    @state() private positions: Record<string, NodePos> = {};
    @state() private layoutReady = false;
    @state() private selectedId: string | null = null;
    /**
     * The selected connection, when one is. A graph has two kinds of thing to delete and only one
     * of them is a node; without this, removing a link meant finding the step, opening its panel
     * and unticking a precondition.
     */
    @state() private selectedEdge: {from: string; to: string; comp: boolean} | null = null;
    /** Node the pointer is over in monitoring view — drives the diagnostic hover tooltip. */
    @state() private hoverId: string | null = null;
    @state() private showMeta = false;
    @state() private layoutError: string | null = null;
    /** When true, the graph overlays the whole viewport (expand button). */
    @state() private fullscreen = false;
    /** When true, animated tokens flow along the sequence edges (BPMN token simulation). */
    @state() private flowOn = true;
    /** Token speed in px/second, adjustable via the viewbar slider. */
    @state() private flowSpeed = 260;
    /** Definition view: when on, nodes are tinted by their stopped/waiting task count (heatmap). */
    @state() private heatmapOn = false;
    /** Heatmap window: only tasks started within the last N days are counted. */
    @state() private heatDays = 30;
    /** Max windowed heat across nodes, recomputed each render so the tint scale is relative. */
    private heatMax = 0;

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
    /** the token's distance along the path on the previous frame, to detect guard-crossings once. */
    private flowPrevPosD = 0;

    // ── Focus interaction ───────────────────────────────────────────────────────
    /**
     * 'auto' cycles every path; 'reachable' (click a node) keeps that node's ancestors +
     * descendants and dims the rest; 'path' (alt+click) shows a single path through the node and
     * cycles to the next on each further alt+click. Both hold in a monitoring (process) view,
     * where the token animation is off — see focusSets().
     */
    private focusMode: "auto" | "reachable" | "path" = "auto";
    private focusNodeId: string | null = null;
    /** The paths currently animated — all of them, or the ones passing through the focus node. */
    private activePaths: string[][] = [];
    /** The focused sub-graph to keep lit, recomputed each render (null = nothing dims). */
    private focusPaint: {nodes: Set<string>; edges: Set<string>} | null = null;

    /** Distributed edge routes ("from->to" → polyline), set by renderEdges and reused by the
     * token (pathGeometry) and guard chips so they follow the exact painted lines. */
    private edgeCache = new Map<string, Pt[]>();

    private draggingId: string | null = null;
    private dragOffset = {x: 0, y: 0};
    private svgEl: SVGSVGElement | null = null;
    /** Track which step ids already have an ELK-computed position so we only
     *  re-layout genuinely new nodes, not ones the user has repositioned. */
    private elkPositioned = new Set<string>();

    // ── Edge drawing (ctrl+drag from a node to another) ─────────────────────────
    /** The source node id while dragging a new precondition line, else null. */
    private linkingFrom: string | null = null;
    /** Live cursor position (scene coords) while drawing, for the rubber-band line. */
    @state() private linkCursor: Pt | null = null;
    /** The node currently hovered as a drop target while drawing. */
    @state() private linkHoverId: string | null = null;

    // ── Zoom / pan viewport ─────────────────────────────────────────────────────
    /** Scene→screen transform: screen = scene * zoomK + pan. */
    @state() private zoomK = 1;
    @state() private panX = 0;
    @state() private panY = 0;
    /** Measured size of the visible canvas area (drives fit + minimap viewport rect). */
    @state() private viewW = 0;
    @state() private viewH = 0;
    private didInitialFit = false;
    private viewportSetup = false;
    private resizeObs?: ResizeObserver;
    /** Background pan drag state. */
    private panning = false;
    private panMoved = false;
    private panStart = {x: 0, y: 0, panX: 0, panY: 0};
    /** True while dragging inside the minimap to scrub the viewport. */
    private miniDrag = false;

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
                    this.didInitialFit = false;   // re-fit the new graph in view
                    this.runElkLayout();
                }
            } catch {
                /* keep previous */
            }
        }
        // Keyed on the graph itself, not on the `value` attribute it may have arrived in. An edit
        // made in the editor — a node added, deleted, or rewired — changes `wf` and never touches
        // `value`, so deriving these from `value` left the animation cycling the paths of the graph
        // as it was before the edit, alt+click cycling paths through nodes that were gone, and new
        // nodes on no path at all. It only looked intermittent because a host that echoes the
        // edited document back re-set `value` and papered over it — unless the round trip produced
        // the identical JSON, in which case nothing was pushed and the staleness stuck.
        if (changed.has("wf")) {
            this.refreshFlowPaths();
        }
        if (changed.has("overlay")) {
            try {
                this.overlayData = this.overlay ? JSON.parse(this.overlay) : {};
            } catch {
                this.overlayData = {};
            }
        }
        // Keep the token-flow loop in sync with the toggle and layout readiness. A monitoring
        // overlay turns the graph into a live monitor, so the simulation steps aside for it.
        if (this.flowOn && this.layoutReady && !this.isMonitoring()) this.startFlow();
        else this.stopFlow();

        // The canvas only exists once layout is ready — wire up viewport measuring/zoom then.
        this.ensureViewportSetup();

        // Once the layout is ready and the viewport is measured, fit the whole graph in view once.
        if (this.layoutReady && this.viewW > 0 && !this.didInitialFit) {
            this.didInitialFit = true;
            this.fitToView();
        }

        // Expanding/collapsing resizes the canvas to a whole new box; re-measure and auto-fit so the
        // graph fills the new viewport instead of keeping its previous pan/zoom. rAF lets the
        // fullscreen layout settle first; reading clientWidth/Height forces the reflow it needs.
        if (changed.has("fullscreen") && this.layoutReady) {
            requestAnimationFrame(() => {
                const wrap = (this.renderRoot as ParentNode)
                    .querySelector(".canvas-wrap") as HTMLElement | null;
                if (wrap) { this.viewW = wrap.clientWidth; this.viewH = wrap.clientHeight; }
                this.fitToView();
            });
        }
    }

    /** Attach the resize observer and wheel-zoom to the canvas once it is in the DOM (idempotent). */
    private ensureViewportSetup() {
        if (this.viewportSetup) return;
        const root = this.renderRoot as ParentNode;
        const svg = root.querySelector("svg.canvas") as SVGSVGElement | null;
        const wrap = root.querySelector(".canvas-wrap") as HTMLElement | null;
        if (!svg || !wrap) return;
        this.viewportSetup = true;
        this.svgEl = svg;
        const measure = () => { this.viewW = wrap.clientWidth; this.viewH = wrap.clientHeight; };
        measure();
        this.resizeObs = new ResizeObserver(measure);
        this.resizeObs.observe(wrap);
        // Native listener (not @wheel) so we can preventDefault the page scroll while zooming.
        svg.addEventListener("wheel", this.onWheel, {passive: false});
    }

    disconnectedCallback() {
        super.disconnectedCallback();
        this.stopFlow();
        this.resizeObs?.disconnect();
        this.svgEl?.removeEventListener("wheel", this.onWheel);
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
        const links = linksOf(step).filter(l => l.stepId !== otherId);
        if (checked) links.push({stepId: otherId});
        this.writeLinks(step.id, links);
    }

    /** Sets the guard on one link, dropping the field when it is cleared. */
    private setGuard(step: WorkflowStep, fromId: string, expression: string) {
        const trimmed = expression.trim();
        this.writeLinks(step.id, linksOf(step).map(l =>
            l.stepId === fromId ? {stepId: l.stepId, expression: trimmed || undefined} : l));
    }

    /**
     * Writes the links back in the one shape that can hold a guard, and clears the two older
     * spellings so a definition never says the same thing twice in two places. A step left with no
     * links drops the field entirely rather than carrying an empty array.
     */
    private writeLinks(stepId: string, links: Precondition[]) {
        this.updateStep(stepId, {
            preconditions: links.length ? links : undefined,
            preconditionStepIds: undefined,
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

    /**
     * Removes a step and every reference any other step held to it.
     *
     * <p>A step id is referenced from three places, and a leftover in any of them is a definition
     * that no longer loads: {@code preconditionStepId}, {@code preconditionStepIds}, and the
     * {@code compensationStepId} of a rollbackable step. The last one used to be missed, which
     * left a step pointing its rollback at something that was not there any more.
     *
     * <p>An emptied precondition list drops the field rather than persisting as `[]`, so deleting
     * the only input of a step leaves the same JSON as never having given it one. {@code
     * rollbackable} is left alone: whether the step still means to roll back is the author's call,
     * and the dangling half — the id — is what had to go.
     */
    private deleteStep(id: string) {
        this.wf = {
            ...this.wf,
            steps: this.wf.steps
                .filter(s => s.id !== id)
                .map(s => {
                    const next = {...s};
                    if (next.preconditionStepId === id) next.preconditionStepId = undefined;
                    if (next.preconditionStepIds) {
                        const kept = next.preconditionStepIds.filter(p => p !== id);
                        next.preconditionStepIds = kept.length ? kept : undefined;
                    }
                    if (next.preconditions) {
                        const kept = next.preconditions.filter(p => p.stepId !== id);
                        next.preconditions = kept.length ? kept : undefined;
                    }
                    if (next.compensationStepId === id) next.compensationStepId = undefined;
                    return next;
                }),
        };
        const {[id]: _, ...rest} = this.positions;
        this.positions = rest;
        this.elkPositioned.delete(id);
        if (this.selectedId === id) this.selectedId = null;
        this.selectedEdge = null;      // it may have been an edge of the step just removed
        this.runElkLayout();
        this.emit();
    }

    /**
     * Removes one connection, leaving both steps in place: a sequence edge is one precondition of
     * its target, a compensation edge is the rollback pointer of its source.
     */
    private deleteEdge(edge: {from: string; to: string; comp: boolean}) {
        if (edge.comp) {
            this.updateStep(edge.from, {compensationStepId: undefined});
        } else {
            const target = this.wf.steps.find(s => s.id === edge.to);
            if (target) {
                this.writeLinks(edge.to, linksOf(target).filter(l => l.stepId !== edge.from));
            }
        }
        this.selectedEdge = null;
    }

    /**
     * What the Delete key removes: the selected connection, or the selected step and every
     * reference to it. Nothing at all in a read-only view, and nothing while the caret is in a
     * field of the side panel — where Delete means delete a character, and taking the step out
     * from under someone editing its name would be the worst possible reading of the key.
     */
    private onKeyDown(e: KeyboardEvent) {
        if (this.readOnly || (e.key !== "Delete" && e.key !== "Backspace")) return;
        const target = e.composedPath()[0] as HTMLElement | undefined;
        const tag = target?.tagName?.toLowerCase();
        if (tag === "input" || tag === "textarea" || tag === "select" || target?.isContentEditable) {
            return;
        }
        if (this.selectedEdge) {
            e.preventDefault();
            this.deleteEdge(this.selectedEdge);
        } else if (this.selectedId) {
            e.preventDefault();
            this.deleteStep(this.selectedId);
        }
    }

    // ── Drag & drop ───────────────────────────────────────────────────────────

    private onNodeMouseDown(e: MouseEvent, id: string) {
        if (e.shiftKey) { if (!this.readOnly) this.startLink(e, id); return; } // shift+drag = draw a line
        if (this.readOnly) return;
        if (e.altKey) return; // alt is a focus click (handled on click), not a drag
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

    // ── Edge drawing ────────────────────────────────────────────────────────────

    private startLink(e: MouseEvent, id: string) {
        e.preventDefault();
        e.stopPropagation(); // don't let the canvas start a pan
        this.svgEl = (e.currentTarget as SVGElement).closest("svg") as SVGSVGElement;
        this.linkingFrom = id;
        this.linkCursor = this.toSvgPoint(e);
        this.linkHoverId = null;
        window.addEventListener("mousemove", this.onLinkMove);
        window.addEventListener("mouseup", this.onLinkUp);
    }

    private onLinkMove = (e: MouseEvent) => {
        if (!this.linkingFrom) return;
        this.linkCursor = this.toSvgPoint(e);
        this.linkHoverId = this.nodeAt(this.linkCursor);
    };

    private onLinkUp = () => {
        const from = this.linkingFrom, to = this.linkHoverId;
        this.linkingFrom = null;
        this.linkCursor = null;
        this.linkHoverId = null;
        window.removeEventListener("mousemove", this.onLinkMove);
        window.removeEventListener("mouseup", this.onLinkUp);
        if (from && to && from !== to) this.createLink(from, to);
    };

    /** The step whose box contains the point (for the drop target), excluding the link source. */
    private nodeAt(pt: Pt): string | null {
        for (const s of this.wf.steps ?? []) {
            if (s.id === this.linkingFrom) continue;
            const b = this.boxForId(s.id);
            if (b && Math.abs(pt.x - b.x) <= b.w / 2 && Math.abs(pt.y - b.y) <= b.h / 2) return s.id;
        }
        return null;
    }

    /**
     * Create a normal precondition line `from → to` (from becomes a precondition of to). At most
     * one normal line may exist between two nodes: a duplicate, the reverse direction, or a line
     * that would close a cycle is rejected silently.
     */
    private createLink(from: string, to: string) {
        const toStep = this.wf.steps.find(s => s.id === to);
        const fromStep = this.wf.steps.find(s => s.id === from);
        if (!toStep || !fromStep) return;
        if (toStep.type === "START") return;                     // START never has preconditions
        if (preconditionsOf(toStep).includes(from)) return;      // already exists
        if (preconditionsOf(fromStep).includes(to)) return;      // reverse line already exists
        if (this.ancestorsOf(from).has(to)) return;              // would close a cycle
        this.togglePrecondition(toStep, from, true);
    }

    /** All transitive preconditions (ancestors) of a step. */
    private ancestorsOf(id: string): Set<string> {
        const byId = new Map((this.wf.steps ?? []).map(s => [s.id, s] as const));
        const seen = new Set<string>();
        const stack = [...preconditionsOf(byId.get(id) ?? {} as WorkflowStep)];
        while (stack.length) {
            const cur = stack.pop()!;
            if (seen.has(cur)) continue;
            seen.add(cur);
            const s = byId.get(cur);
            if (s) stack.push(...preconditionsOf(s));
        }
        return seen;
    }

    private toSvgPoint(e: MouseEvent): {x: number; y: number} {
        if (!this.svgEl) return {x: 0, y: 0};
        const rect = this.svgEl.getBoundingClientRect();
        // screen → scene: undo the pan/zoom transform applied to the scene group
        return {
            x: (e.clientX - rect.left - this.panX) / this.zoomK,
            y: (e.clientY - rect.top - this.panY) / this.zoomK,
        };
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

    /** Tight bounding box of all laid-out nodes (scene coords), padded. Null if empty. */
    private graphBounds(pad = 60): {minX: number; minY: number; w: number; h: number} | null {
        const steps = (this.wf.steps ?? []).filter(s => this.positions[s.id]);
        if (steps.length === 0) return null;
        let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
        for (const s of steps) {
            const p = this.positions[s.id], sz = sizeOf(s.type);
            minX = Math.min(minX, p.x); minY = Math.min(minY, p.y);
            maxX = Math.max(maxX, p.x + sz.w); maxY = Math.max(maxY, p.y + sz.h + 18); // +caption
        }
        return {minX: minX - pad, minY: minY - pad, w: maxX - minX + 2 * pad, h: maxY - minY + 2 * pad};
    }

    private clampZoom(k: number) { return Math.max(0.1, Math.min(2.5, k)); }

    /** Scale + centre the whole graph so it all fits inside the visible canvas area. */
    private fitToView = () => {
        const b = this.graphBounds();
        if (!b || this.viewW === 0 || this.viewH === 0) return;
        const raw = Math.min(this.viewW / b.w, this.viewH / b.h);
        // Embedded, start from the standard size (1:1) and only shrink to fit — never enlarge past
        // natural size to fill an empty panel. Expanded (fullscreen), the point is to use the whole
        // screen, so scale up to fill too. Either way, centre it in the viewport.
        const k = this.clampZoom(this.fullscreen ? raw : Math.min(1, raw));
        this.zoomK = k;
        this.panX = (this.viewW - k * b.w) / 2 - k * b.minX;
        this.panY = (this.viewH - k * b.h) / 2 - k * b.minY;
    };

    private onWheel = (e: WheelEvent) => {
        e.preventDefault();
        if (!this.svgEl) return;
        const rect = this.svgEl.getBoundingClientRect();
        const cx = e.clientX - rect.left, cy = e.clientY - rect.top;
        const newK = this.clampZoom(this.zoomK * Math.exp(-e.deltaY * 0.0015));
        // keep the scene point under the cursor fixed while zooming
        const sx = (cx - this.panX) / this.zoomK, sy = (cy - this.panY) / this.zoomK;
        this.panX = cx - sx * newK; this.panY = cy - sy * newK;
        this.zoomK = newK;
    };

    private onCanvasMouseDown = (e: MouseEvent) => {
        // Any click in the canvas — on a node, on a link, on nothing — takes keyboard focus, which
        // is what makes Delete work on whatever that click selects. Runs before the guards below
        // because a click that starts a node drag has to focus too.
        (this.renderRoot as unknown as ParentNode)
            .querySelector<HTMLElement>(".root")?.focus({preventScroll: true});
        if (this.draggingId || this.linkingFrom || e.button !== 0 || e.shiftKey || e.altKey
                || e.ctrlKey || e.metaKey) return; // node drag / link / focus click
        this.panning = true; this.panMoved = false;
        this.panStart = {x: e.clientX, y: e.clientY, panX: this.panX, panY: this.panY};
        window.addEventListener("mousemove", this.onPanMove);
        window.addEventListener("mouseup", this.onPanUp);
    };
    private onPanMove = (e: MouseEvent) => {
        if (!this.panning) return;
        const dx = e.clientX - this.panStart.x, dy = e.clientY - this.panStart.y;
        if (Math.abs(dx) + Math.abs(dy) > 3) this.panMoved = true;
        this.panX = this.panStart.panX + dx; this.panY = this.panStart.panY + dy;
    };
    private onPanUp = () => {
        this.panning = false;
        window.removeEventListener("mousemove", this.onPanMove);
        window.removeEventListener("mouseup", this.onPanUp);
        if (!this.panMoved) {                                 // a plain click clears the selection
            this.selectedId = null;
            this.selectedEdge = null;
            this.clearFocus();
        }
    };

    /** Recentre the viewport on a scene point (used by minimap click/drag). */
    private centerOn(sceneX: number, sceneY: number) {
        this.panX = this.viewW / 2 - this.zoomK * sceneX;
        this.panY = this.viewH / 2 - this.zoomK * sceneY;
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
            const nodeId = k.slice(0, k.lastIndexOf("|"));
            const box = this.boxForId(nodeId)!;
            const type = (this.wf.steps ?? []).find(s => s.id === nodeId)!.type;
            members.sort((a, b) => a.perp - b.perp);
            const n = members.length;
            members.forEach((m, i) => {
                const f = n <= 1 ? 0.5 : 0.28 + 0.44 * (i / (n - 1)); // spread across the middle of the side
                const pt: Pt = sd === "R" ? {x: box.x + box.w / 2, y: box.y - box.h / 2 + box.h * f}
                    : sd === "L" ? {x: box.x - box.w / 2, y: box.y - box.h / 2 + box.h * f}
                    : sd === "T" ? {x: box.x - box.w / 2 + box.w * f, y: box.y - box.h / 2}
                    : {x: box.x - box.w / 2 + box.w * f, y: box.y + box.h / 2};
                // Pull the point onto the real outline (circle/diamond) so no white gap remains.
                attach[m.edge][m.role] = snapToShape(box.x, box.y, type, pt, sd);
            });
        }

        // Route one edge at a time, letting each avoid overlapping the lines already placed
        // (sequence edges first, compensation last — so comp lines yield to the normal flow).
        const priorSegs: [Pt, Pt][] = [];
        return raw.map((e, idx) => {
            const [sS, tS] = sides[idx];
            const obstacles: Box[] = [];
            for (const s of steps) {
                if (s.id === e.from || s.id === e.to) continue;
                const box = this.boxForId(s.id);
                if (box) obstacles.push(box);
            }
            const pts = routeThrough(attach[idx][0], sS, attach[idx][1], tS, obstacles, priorSegs);
            for (let i = 0; i < pts.length - 1; i++) priorSegs.push([pts[i], pts[i + 1]]);
            return {key: `${e.from}->${e.to}`, from: e.from, to: e.to, comp: e.comp, pts};
        });
    }

    // ── Token-flow animation (path by path) ─────────────────────────────────────

    /**
     * The polyline a token walks for a path (list of step ids): the edge routes joined end to
     * end. The token stays ON the edges — while it crosses a node it is hidden (`hidden` ranges),
     * since the node's own ping already marks the passage. `marks` gives the distance at which
     * the token reaches each node (for the ping).
     */
    private pathGeometry(ids: string[]): {pts: Pt[]; marks: {id: string; d: number}[]; hidden: {from: number; to: number}[]; segs: {to: string; startD: number; len: number}[]} | null {
        const boxes = ids.map(id => this.boxForId(id));
        if (boxes.some(b => !b)) return null;
        if (ids.length < 2) {
            return {pts: [{x: boxes[0]!.x, y: boxes[0]!.y}], marks: [{id: ids[0], d: 0}], hidden: [], segs: []};
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
        // Where each edge's own route begins along the path, and its length — so a guard chip
        // (placed at fraction 0.38 of an edge) can be located as a distance along the path.
        const segs = [{to: ids[1], startD: 0, len: polylineLength(edges[0])}];
        for (let j = 1; j < edges.length; j++) {
            const d0 = polylineLength(pts);   // at node j's entry border (end of the previous edge)
            pts.push(edges[j][0]);            // node j's exit border (start of this edge)
            const d1 = polylineLength(pts);
            hidden.push({from: d0, to: d1});  // straight span across node j → token hidden here
            marks.push({id: ids[j], d: d0});  // ping node j as the token reaches it
            pts.push(...edges[j].slice(1));
            segs.push({to: ids[j + 1], startD: d1, len: polylineLength(edges[j])});
        }
        marks.push({id: ids[ids.length - 1], d: polylineLength(pts)}); // sink node arrival
        return {pts, marks, hidden, segs};
    }

    private isEdgeSelected(edge: {from: string; to: string; comp: boolean}) {
        const s = this.selectedEdge;
        return !!s && s.from === edge.from && s.to === edge.to && s.comp === edge.comp;
    }

    /** Selecting a connection deselects the step, and the other way round: one thing at a time. */
    private onEdgeClick(e: MouseEvent, edge: {from: string; to: string; comp: boolean}) {
        e.stopPropagation();
        if (this.readOnly) return;
        this.selectedId = null;
        this.selectedEdge = this.isEdgeSelected(edge)
            ? null
            : {from: edge.from, to: edge.to, comp: edge.comp};
    }

    private onNodeClick(e: MouseEvent, id: string) {
        e.stopPropagation();
        this.selectedEdge = null;                              // one thing selected at a time
        if (e.shiftKey) return;                                // shift is line drawing, not focus
        if (e.altKey) { this.focusNextPath(id); return; }      // one path through the node, cycling
        // Plain click: select the node AND filter to what's connected to it (its ancestors +
        // descendants), dimming the rest.
        this.selectedId = id;
        this.focusReachable(id);
    }

    /** Root→sink paths passing through a node (falls back to all paths if none). */
    private pathsThrough(id: string): string[][] {
        const through = this.flowPaths.filter(p => p.includes(id));
        return through.length ? through : this.flowPaths;
    }

    /**
     * Re-derives the paths the animation and the focus work over, after any change to the graph.
     *
     * <p>A focus on a node that is still there survives the edit — losing it every time a field is
     * typed into would be its own annoyance — and one on a node that has just been deleted falls
     * back to animating everything. The path index is taken modulo the new length so the token
     * carries on from a path that exists rather than pointing past the end of the list.
     */
    private refreshFlowPaths() {
        this.flowPaths = allPaths(this.wf.steps ?? []);
        const focusStillThere = this.focusNodeId != null
            && (this.wf.steps ?? []).some(s => s.id === this.focusNodeId);
        if (this.focusMode !== "auto" && focusStillThere) {
            this.activePaths = this.pathsThrough(this.focusNodeId!);
        } else {
            this.focusMode = "auto";
            this.focusNodeId = null;
            this.activePaths = this.flowPaths;
        }
        const paths = this.activePaths.length ? this.activePaths : this.flowPaths;
        this.flowPathIndex = paths.length ? this.flowPathIndex % paths.length : 0;
        this.pulsedThisPath = new Set();
    }

    /**
     * Selecting a node changes what the simulation would animate; it does not decide whether it
     * animates. A paused simulation stays paused — the pause is the operator's, and having a click
     * on a node undo it made the play/pause button look broken.
     */
    private focusReachable(id: string) {
        this.focusMode = "reachable";
        this.focusNodeId = id;
        this.activePaths = this.pathsThrough(id);
        if (this.flowOn && !this.isMonitoring()) this.restartFlow(); // never wakes it, only re-aims it
        this.requestUpdate();
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
        this.pulsedThisPath = new Set();
        if (this.flowOn && !this.isMonitoring()) {
            this.flowStartTs = performance.now(); // restart the token from this path's beginning
        }
        this.requestUpdate();
    }

    private clearFocus() {
        this.focusMode = "auto";
        this.focusNodeId = null;
        this.activePaths = this.flowPaths;
        this.restartFlow();
        this.requestUpdate();
    }

    /**
     * The focused sub-graph — the nodes and edge keys to keep lit — or null in 'auto' focus, where
     * nothing is dimmed. 'reachable' (plain click) keeps every path through the clicked node,
     * 'path' (alt+click) only the one currently selected. Computed at render time and painted as a
     * class, so the dimming also holds in a process (monitoring) view, where the token animation —
     * which dims the same way while it runs — is switched off.
     */
    private focusSets(): {nodes: Set<string>; edges: Set<string>} | null {
        if (this.focusMode === "auto") return null;
        const paths = this.activePaths.length ? this.activePaths : this.flowPaths;
        if (paths.length === 0) return null;
        const universe = this.focusMode === "path" ? [paths[this.flowPathIndex % paths.length]] : paths;
        const nodes = new Set<string>(), edges = new Set<string>();
        for (const p of universe) {
            for (let i = 0; i < p.length; i++) {
                nodes.add(p[i]);
                if (i > 0) edges.add(`${p[i - 1]}->${p[i]}`);
            }
        }
        return {nodes, edges};
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
        const speed = this.flowSpeed; // px per second (viewbar slider)
        const pausePx = 55;        // brief gap between paths
        const DWELL_MS = 1800;     // a long-running node holds the token this long…
        const PING_MS = 600;       // …re-pinging at this cadence (≈3 pulses) to signal "this takes a while"
        const SLOW_TIMEOUT_MS = 30000;

        const byId = new Map((this.wf.steps ?? []).map(s => [s.id, s] as const));
        // Long-running steps — the token pauses on them and the node pulses several times:
        // USER_TASK (a human is in the loop), WAIT_FOR_MESSAGE / TIMER (they wait by nature),
        // and any step with a high timeout (assumed slow).
        const SLOW_TYPES = new Set<StepType>(["USER_TASK", "WAIT_FOR_MESSAGE", "TIMER"]);
        // An AND-join synchronises: it waits for ALL its incoming branches. Treat it as a dwell
        // node so the token pauses there and we can light up the branches it is waiting for.
        const isAndJoin = (id: string) => {
            const s = byId.get(id);
            return !!s && s.type === "JOIN" && s.joinType !== "XOR" && preconditionsOf(s).length > 1;
        };
        const isSlow = (id: string) => {
            const s = byId.get(id);
            return !!s && (SLOW_TYPES.has(s.type) || (s.timeout ?? 0) >= SLOW_TIMEOUT_MS || isAndJoin(id));
        };

        const stops = geo.marks;
        let schedMs = (len / speed) * 1000;
        for (const m of stops) if (isSlow(m.id)) schedMs += DWELL_MS;
        const pauseMs = (pausePx / speed) * 1000;
        const elapsed = now - this.flowStartTs;

        if (elapsed >= schedMs + pauseMs) {
            // In 'path' focus we loop the chosen path; otherwise advance to the next one.
            if (this.focusMode !== "path") this.flowPathIndex = (idx + 1) % paths.length;
            this.flowStartTs = now;
            this.pulsedThisPath = new Set();
            this.flowPrevPosD = 0;
            return;
        }

        // Walk the polyline at constant speed, holding at each long-running node for DWELL_MS.
        // posD is the token's distance along the path; dwellId is the node it currently sits in.
        let acc = 0, prevD = 0, posD = len;
        let dwellId: string | null = null;
        for (const m of stops) {
            const segMs = ((m.d - prevD) / speed) * 1000;
            if (elapsed < acc + segMs) { posD = prevD + (segMs <= 0 ? 0 : (elapsed - acc) / segMs) * (m.d - prevD); break; }
            acc += segMs;
            if (isSlow(m.id)) {
                if (elapsed < acc + DWELL_MS) { posD = m.d; dwellId = m.id; break; }
                acc += DWELL_MS;
            }
            prevD = m.d;
            posD = m.d;
        }

        // Position the token; hide it while it crosses (or dwells inside) a node, and during the
        // brief inter-path pause.
        const clamped = Math.min(posD, len);
        const p = polylinePointAt(geo.pts, clamped / len);
        token.setAttribute("cx", String(p.x));
        token.setAttribute("cy", String(p.y));
        const crossingNode = geo.hidden.some(hr => clamped >= hr.from && clamped <= hr.to);
        token.style.opacity = (elapsed <= schedMs && !crossingNode && !dwellId) ? "1" : "0";

        // On an error/compensation path (its last edge is a compensation edge), only the failing
        // rollbackable node pings red to flag the failure. The compensation step is the
        // (successful) recovery, so it — and the token — keep their normal colour.
        const errorNodes = new Set<string>();
        for (let i = 1; i < path.length; i++) {
            const s = byId.get(path[i - 1]);
            if (s && s.rollbackable && s.compensationStepId === path[i]) errorNodes.add(path[i - 1]);
        }

        // Ping each node once, as the token reaches it (red only on the failing node)…
        for (const m of stops) {
            if (posD >= m.d && !this.pulsedThisPath.has(m.id)) {
                this.pulseAt[m.id] = now;
                this.pulseColor[m.id] = errorNodes.has(m.id) ? "#dc2626" : "";
                this.pulsedThisPath.add(m.id);
            }
        }

        // As the token reaches the middle of a guarded edge, pop its precondition chip once —
        // a one-shot pulse (via the Web Animations API, so it replays cleanly each pass).
        for (const seg of geo.segs) {
            const s = byId.get(seg.to);
            if (!s?.preconditionExpression) continue;
            const gd = seg.startD + 0.38 * seg.len;     // where the chip sits (renderGuard uses 0.38)
            if (this.flowPrevPosD < gd && posD >= gd) {
                const q = `.guard[data-guard="${seg.to}"]`;
                (root.querySelector(`${q} .guard-chip`) as SVGGElement | null)?.animate?.(
                    [{transform: "scale(1.22)"}, {transform: "scale(1.6)", offset: 0.4}, {transform: "scale(1.22)"}],
                    {duration: 520, easing: "ease-out"});
                (root.querySelector(`${q} .guard-halo`) as SVGElement | null)?.animate?.(
                    [{opacity: "0.38"}, {opacity: "0.8", offset: 0.4}, {opacity: "0.38"}],
                    {duration: 520, easing: "ease-out"});
            }
        }
        this.flowPrevPosD = posD;
        // …but a long-running node keeps re-pinging while the token dwells inside it.
        if (dwellId && now - (this.pulseAt[dwellId] ?? 0) >= PING_MS) {
            this.pulseAt[dwellId] = now;
        }

        // The currently-animated path's edges (brightest) and, in a focus mode, the "universe"
        // to keep un-dimmed (the reachable sub-graph, or just the chosen path). In auto mode
        // there is no universe, so everything but the animated path dims.
        const activeEdges = new Set<string>();
        for (let i = 1; i < path.length; i++) activeEdges.add(`${path[i - 1]}->${path[i]}`);
        // While the token synchronises at an AND-join, light up ALL its incoming branches (and keep
        // their source nodes lit) — a visual "waiting for every branch to complete".
        const syncNodes = new Set<string>();
        if (dwellId && isAndJoin(dwellId)) {
            for (const pre of preconditionsOf(byId.get(dwellId)!)) {
                activeEdges.add(`${pre}->${dwellId}`);
                syncNodes.add(pre);
            }
        }
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

        // Dim nodes that don't take part in the animated path (mirroring the edges), and render
        // the node pings. In a focus mode the whole focus universe stays lit; in auto mode only
        // the current path's nodes stay lit.
        const pathNodes = new Set(path);
        for (const s of this.wf.steps ?? []) {
            const g = root.querySelector?.(`.node[data-node="${s.id}"]`) as SVGGElement | null;
            const onPath = pathNodes.has(s.id) || syncNodes.has(s.id);
            const dim = focusNodes ? (!focusNodes.has(s.id) && !onPath) : !onPath;
            if (g) g.classList.toggle("dim", dim);
            const ring = root.querySelector?.(`[data-pulse="${s.id}"]`) as SVGCircleElement | null;
            if (!ring) continue;
            const t0 = this.pulseAt[s.id];
            const dt = t0 ? (now - t0) / 1000 : Infinity;
            // A failing node trembles briefly while its red ping is fresh (CSS @keyframes ec-shake).
            if (g) g.classList.toggle("err", errorNodes.has(s.id) && dt < 0.5);
            if (dt > 0.6) { ring.setAttribute("opacity", "0"); continue; }
            const k = dt / 0.6;
            const base = Math.max(sizeOf(s.type).w, sizeOf(s.type).h) / 2;
            ring.style.fill = this.pulseColor[s.id] || "";  // red on error nodes, else default
            ring.setAttribute("r", String(base + k * 16));
            ring.setAttribute("opacity", String((1 - k) * 0.45));
        }
    }

    // ── Render ────────────────────────────────────────────────────────────────

    /** The rubber-band line drawn from the source node to the cursor while ctrl+dragging a link. */
    private renderLinkDraft() {
        if (!this.linkingFrom || !this.linkCursor) return nothing;
        const b = this.boxForId(this.linkingFrom);
        if (!b) return nothing;
        const start = borderTowards(b, this.linkCursor.x, this.linkCursor.y);
        return svg`<line class="link-draft" x1="${start.x}" y1="${start.y}"
                         x2="${this.linkCursor.x}" y2="${this.linkCursor.y}"/>`;
    }

    /** A modux-style minimap: the whole graph in miniature with the current viewport framed. */
    private renderMinimap() {
        const b = this.graphBounds();
        if (!b || (this.wf.steps ?? []).length < 2 || this.viewW === 0) return nothing;
        const MW = 168, MH = 116;
        const scale = Math.min(MW / b.w, MH / b.h);
        const mw = b.w * scale, mh = b.h * scale;
        // The scene rectangle currently visible on screen, in scene coords.
        const vx = -this.panX / this.zoomK, vy = -this.panY / this.zoomK;
        const vw = this.viewW / this.zoomK, vh = this.viewH / this.zoomK;
        const scrub = (e: MouseEvent) => {
            const box = (e.currentTarget as Element).getBoundingClientRect();
            this.centerOn(b.minX + (e.clientX - box.left) / scale, b.minY + (e.clientY - box.top) / scale);
        };
        return html`
            <div class="minimap" style="width:${mw}px;height:${mh}px"
                 title="Minimap — click or drag to navigate"
                 @mousedown="${(e: MouseEvent) => { e.stopPropagation(); this.miniDrag = true; scrub(e); }}"
                 @mousemove="${(e: MouseEvent) => { if (this.miniDrag) scrub(e); }}"
                 @mouseup="${() => { this.miniDrag = false; }}"
                 @mouseleave="${() => { this.miniDrag = false; }}">
                <svg viewBox="0 0 ${b.w} ${b.h}" width="${mw}" height="${mh}">
                    ${(this.wf.steps ?? []).map(s => {
                        const p = this.positions[s.id];
                        if (!p) return nothing;
                        const sz = sizeOf(s.type), st = styleOf(s.type);
                        return svg`<rect x="${p.x - b.minX}" y="${p.y - b.minY}" width="${sz.w}" height="${sz.h}"
                                         rx="4" fill="${st.fill}" stroke="${st.stroke}" stroke-width="2"/>`;
                    })}
                    <rect class="mini-view" x="${vx - b.minX}" y="${vy - b.minY}" width="${vw}" height="${vh}"/>
                </svg>
            </div>`;
    }

    /** True when a monitoring overlay is present — the graph is a live monitor, not a simulator. */
    private isMonitoring() { return Object.keys(this.overlayData).length > 0; }

    /**
     * True only when the overlay carries per-step execution *state* (a single-process view, where
     * dimming the not-yet-visited parts is meaningful). A definition view attaches a counts-only
     * overlay (how many live processes sit on each step) with no state — there is no single
     * execution to trace, so nothing should be dimmed: the whole graph stays active by default.
     */
    private hasStateOverlay() {
        return Object.values(this.overlayData).some(o => !!o?.state);
    }

    /** In a monitoring overlay, a step is "visited" once the process has reached it (any state
     *  other than not-yet-started PENDING). Used to dim the parts the process hasn't passed. */
    private isVisited(id: string): boolean {
        const st = this.overlayData[id]?.state;
        return !!st && st !== "PENDING";
    }

    /** True when the overlay carries per-step heat histograms — i.e. this is a definition view that
     *  can offer the stopped/waiting heatmap. The single-process monitoring view ships no heat. */
    private hasHeatData() {
        return Object.values(this.overlayData).some(o => Array.isArray(o?.heat));
    }

    /** A step's stopped/waiting task count within the last `heatDays` days: the sum of the histogram
     *  buckets `[0, heatDays)`. */
    private heatValue(id: string): number {
        const heat = this.overlayData[id]?.heat;
        if (!heat) return 0;
        let sum = 0;
        const n = Math.min(this.heatDays, heat.length);
        for (let i = 0; i < n; i++) sum += heat[i] ?? 0;
        return sum;
    }

    /** Tint intensity (0–100) for a node's heat, relative to the hottest node in the current window.
     *  A mild gamma + a floor keep any non-zero step visibly warm rather than washed out. */
    private heatIntensity(id: string): number {
        const v = this.heatValue(id);
        if (v <= 0 || this.heatMax <= 0) return 0;
        return Math.round(18 + 82 * Math.pow(v / this.heatMax, 0.7));
    }

    /** Floating view/animation controls (bottom-left, clear of the toolbar and the minimap). */
    private renderViewbar() {
        // In a monitoring view the token simulation is off, so only the zoom controls are shown.
        const sim = this.isMonitoring() ? nothing : html`
            <button class="vbtn" title="${this.flowOn ? "Pause token flow" : "Play token flow"}"
                    @click="${() => { this.flowOn = !this.flowOn; }}">${this.flowOn ? "⏸" : "▶"}</button>
            <input class="vspeed" type="range" min="80" max="520" step="10"
                   title="Animation speed" .value="${String(this.flowSpeed)}"
                   @input="${(e: Event) => { this.flowSpeed = Number((e.target as HTMLInputElement).value); }}"/>`;
        // Definition view: a heatmap of where stopped/waiting tasks pile up, with a last-N-days
        // window. Both operate client-side on the per-step heat histograms already in the overlay.
        const heat = !this.hasHeatData() ? nothing : html`
            <button class="vbtn ${this.heatmapOn ? "on" : ""}" title="Toggle stopped/waiting heatmap"
                    @click="${() => { this.heatmapOn = !this.heatmapOn; }}">🔥</button>
            ${this.heatmapOn ? html`
                <input class="vspeed" type="range" min="1" max="90" step="1"
                       title="Show tasks from the last ${this.heatDays} day(s)"
                       .value="${String(this.heatDays)}"
                       @input="${(e: Event) => { this.heatDays = Number((e.target as HTMLInputElement).value); }}"/>
                <span class="vlabel" title="Heatmap window">${this.heatDays}d</span>
            ` : nothing}`;
        return html`
            <div class="viewbar" @mousedown="${(e: MouseEvent) => e.stopPropagation()}">
                ${sim}
                ${heat}
                <button class="vbtn" title="Fit graph to view" @click="${() => this.fitToView()}">${iconFit}</button>
                ${this.noExpand ? nothing : html`
                    <button class="vbtn" title="${this.fullscreen ? "Collapse" : "Expand"}"
                            @click="${() => { this.fullscreen = !this.fullscreen; }}">${this.fullscreen ? "✕" : "⤢"}</button>`}
            </div>`;
    }

    render() {
        if (!this.layoutReady) {
            return html`<div class="loading">Computing layout…</div>`;
        }

        const steps = this.wf.steps ?? [];
        this.focusPaint = this.focusSets(); // read by renderEdges / renderNode / renderGuard below
        // Relative tint scale for the heatmap: the hottest node in the current window is the max.
        this.heatMax = this.heatmapOn && this.hasHeatData()
            ? steps.reduce((m, s) => Math.max(m, this.heatValue(s.id)), 0)
            : 0;

        return html`
            <!-- tabindex so the graph can hold keyboard focus: Delete has to reach it, and inside
                 an IDE webview nothing else is going to hand it the key. Focus is taken on a click
                 in the canvas rather than on load, so opening a file never steals it. -->
            <div class="root ${this.fullscreen ? "fullscreen" : ""}"
                 tabindex="0" @keydown="${this.onKeyDown}">
                ${this.readOnly ? nothing : this.renderToolbar()}
                ${this.showMeta ? this.renderMeta() : ""}
                ${this.layoutError ? html`<div class="error">⚠ ${this.layoutError}</div>` : ""}
                <div class="workspace">
                    <div class="canvas-wrap">
                        ${this.renderViewbar()}
                        <svg width="100%" height="100%" class="canvas ${this.panning ? "panning" : ""}"
                             @mousedown="${this.onCanvasMouseDown}">
                            <defs>
                                <!-- The arrowhead is what tells you which way a line runs, and at
                                     1:1 zoom the old one was a smudge on a 1.6px line: present,
                                     unreadable. Half again as long, wider at the base, and it
                                     reads as an arrow at the zoom people actually work at. -->
                                <marker id="ec-arrow" markerWidth="13" markerHeight="13"
                                        refX="11" refY="4.5" orient="auto" markerUnits="userSpaceOnUse">
                                    <path d="M0,0 L0,9 L11.5,4.5 z" fill="context-stroke"/>
                                </marker>
                                <filter id="ec-shadow" x="-20%" y="-20%" width="140%" height="150%">
                                    <feDropShadow dx="0" dy="1" stdDeviation="1.2" flood-color="#0f172a"
                                                  flood-opacity="0.10"/>
                                </filter>
                            </defs>
                            <g class="scene" transform="translate(${this.panX},${this.panY}) scale(${this.zoomK})">
                                ${this.renderEdges()}
                                ${steps.map(s => this.renderNode(s))}
                                ${steps.map(s => this.renderGuard(s))}
                                ${this.renderLinkDraft()}
                                ${this.flowOn ? svg`<circle class="flow-token" r="5.5" cx="-100" cy="-100"/>` : nothing}
                            </g>
                        </svg>
                        ${this.renderMinimap()}
                        ${this.renderOverlayTooltip()}
                    </div>
                    ${this.selectedId && !this.readOnly ? this.renderPanel() : ""}
                </div>
            </div>
        `;
    }

    /**
     * Declares the workflow disabled, or lifts it. Archiving implies it, so unticking Disabled
     * clears both — a workflow cannot be archived and yet open for business.
     *
     * <p>Writing the flags always drops a legacy `status`: it is the field this editor used to
     * write, the engine has no such property, and a file carrying it fails to import.
     */
    private setDeclaredDisabled(disabled: boolean) {
        this.updateWf({
            disabled: disabled || undefined,
            archived: disabled ? this.wf.archived : undefined,
            status: undefined,
        });
    }

    private setDeclaredArchived(archived: boolean) {
        this.updateWf({
            archived: archived || undefined,
            disabled: archived ? true : this.wf.disabled,
            status: undefined,
        });
    }

    /** What the definition declares about itself, for the badge. */
    private declaredState(): "ACTIVE" | "DISABLED" | "ARCHIVED" {
        if (this.wf.archived || this.wf.status === "ARCHIVED") return "ARCHIVED";
        if (this.wf.disabled || this.wf.status === "DISABLED") return "DISABLED";
        return "ACTIVE";
    }

    private renderToolbar() {
        const status = this.declaredState();
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
                    <textarea class="inp" rows="2" .value="${wf.description ?? ""}"
                              @change="${(e: Event) => this.updateWf({description: (e.target as HTMLTextAreaElement).value})}"></textarea>
                    <label>Status</label>
                    <label title="No new instances, cron included. The runtime cannot enable a workflow its definition disables.">
                        <input type="checkbox"
                               ?checked="${this.declaredState() !== "ACTIVE"}"
                               @change="${(e: Event) => this.setDeclaredDisabled((e.target as HTMLInputElement).checked)}"/>
                        Disabled
                    </label>
                    <label title="Retired: as disabled, and hidden from the listing.">
                        <input type="checkbox"
                               ?checked="${this.declaredState() === "ARCHIVED"}"
                               @change="${(e: Event) => this.setDeclaredArchived((e.target as HTMLInputElement).checked)}"/>
                        Archived
                    </label>
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
        const mon = this.hasStateOverlay();
        for (const e of edges) {
            const d = bridgedPath(e.pts, prior);
            // In a process (state) view, dim edges the process hasn't traversed (either end unvisited).
            const monDim = mon && !(this.isVisited(e.from) && this.isVisited(e.to)) ? "mon-dim" : "";
            const focusDim = this.focusPaint && !this.focusPaint.edges.has(e.key) ? "focus-dim" : "";
            const sel = this.isEdgeSelected(e) ? "sel" : "";
            out.push(e.comp
                ? svg`<path class="comp-edge ${monDim} ${focusDim} ${sel}" data-comp="${e.from}" data-edge="${e.key}"
                             d="${d}" marker-end="url(#ec-arrow)"/>`
                : svg`<path class="edge ${monDim} ${focusDim} ${sel}" data-edge="${e.key}"
                             d="${d}" marker-end="url(#ec-arrow)"/>`);
            // A 1.6px line is not a click target. This invisible one rides on top of the painted
            // route and is the thing the pointer actually hits — drawn after, so it is never
            // buried by the edges that come later.
            if (!this.readOnly) {
                out.push(svg`<path class="edge-hit" data-hit="${e.key}" d="${d}"
                                   @click="${(ev: MouseEvent) => this.onEdgeClick(ev, e)}"/>`);
            }
            for (let i = 0; i < e.pts.length - 1; i++) prior.push([e.pts[i], e.pts[i + 1]]);
        }
        return out;
    }

    /**
     * The conditions on this step's incoming links, each painted on the link it belongs to — and
     * the step's own condition, if it has one, painted on the step.
     *
     * <p>They used to be the same thing drawn the same way: one chip, on the first incoming edge,
     * for an expression that actually gated the step however it was reached. On a step with two
     * inputs that is a lie about which route it applies to. A condition on a link now sits on that
     * link; a condition on the step sits above the step, where it cannot be read as belonging to
     * one of its routes.
     */
    private renderGuard(step: WorkflowStep) {
        const to = this.positions[step.id];
        if (!to) return svg``;
        const chips: unknown[] = [];

        for (const link of linksOf(step)) {
            const expr = link.expression?.trim();
            if (!expr) continue;
            const edgeKey = `${link.stepId}->${step.id}`;
            const route = this.edgeCache.get(edgeKey) ?? this.routeBetween(link.stepId, step.id, 0);
            if (!route) continue;
            // Sit toward the source end of the edge, clear of the target node's badge.
            chips.push(this.renderGuardChip(polylinePointAt(route, 0.38), expr, step.id, edgeKey));
        }

        const stepExpr = step.preconditionExpression?.trim();
        if (stepExpr) {
            const {w} = sizeOf(step.type);
            // Above the node, centred on it: this one is about the step, not about a way in.
            chips.push(this.renderGuardChip({x: to.x + w / 2, y: to.y - 40}, stepExpr, step.id, ""));
        }
        return chips.length ? svg`${chips}` : svg``;
    }

    private renderGuardChip(at: Pt, expr: string, stepId: string, edgeKey: string) {
        const text = expr.length > 30 ? expr.slice(0, 29) + "…" : expr;
        const w = Math.max(30, text.length * 6.3 + 22);
        const h = 19;
        const focusDim = edgeKey && this.focusPaint && !this.focusPaint.edges.has(edgeKey)
            ? "focus-dim" : "";
        return svg`
            <g class="guard ${focusDim}" data-guard="${stepId}" data-edge="${edgeKey}"
               transform="translate(${at.x}, ${at.y})">
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
            // BPMN gateway diamond. Parallel (FORK / AND-JOIN) shows "+", exclusive (XOR-JOIN) "×".
            const cx = w / 2, cy = h / 2;
            const pts = `${cx},2 ${w - 2},${cy} ${cx},${h - 2} 2,${cy}`;
            const xor = step.type === "JOIN" && step.joinType === "XOR";
            const glyph = xor
                ? svg`<path class="gw-plus" d="M${cx - 8},${cy - 8} L${cx + 8},${cy + 8} M${cx + 8},${cy - 8} L${cx - 8},${cy + 8}" stroke="${st.stroke}"/>`
                : svg`<path class="gw-plus" d="M${cx - 9},${cy} H${cx + 9} M${cx},${cy - 9} V${cy + 9}" stroke="${st.stroke}"/>`;
            shape = svg`
                <polygon class="node-shape gateway" points="${pts}" fill="${st.fill}" stroke="${st.stroke}"/>
                ${glyph}
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

        // Read-only monitoring overlay: state tint / active highlight + a live process-count badge.
        const ov = this.overlayData[step.id];
        const heatOn = this.heatmapOn && this.hasHeatData();
        const heatPct = heatOn ? this.heatIntensity(step.id) : 0;
        const ovCls = ov ? `${ov.active ? "ov-active" : ""} ${ov.state ? "ov-" + ov.state.toLowerCase() : ""}` : "";
        // With the heatmap on the badge narrows to the tasks inside the chosen last-N-days window.
        const count = heatOn ? this.heatValue(step.id) : (ov?.count ?? 0);
        const badge = count > 0 ? svg`
            <g class="ov-count" transform="translate(${w - 5}, 5)">
                <circle r="10"/>
                <text text-anchor="middle" dy="3.6">${count > 99 ? "99+" : count}</text>
            </g>` : nothing;

        // A big green check on executed (COMPLETED) steps, bottom-right corner — reads clearly.
        const done = ov?.state === "COMPLETED" ? svg`
            <g class="ov-done" transform="translate(${w - 6}, ${h - 6})">
                <circle r="12"/>
                <path class="ov-check" d="M -6 0.5 L -1.5 5 L 6 -4.5"/>
            </g>` : nothing;

        const linkCls = `${this.linkHoverId === step.id ? "link-target" : ""} ${this.linkingFrom === step.id ? "link-source" : ""}`;
        const monDim = this.hasStateOverlay() && !this.isVisited(step.id) ? "mon-dim" : "";
        const focusDim = this.focusPaint && !this.focusPaint.nodes.has(step.id) ? "focus-dim" : "";
        return svg`
            <g class="node ${sel} ${ovCls} ${linkCls} ${monDim} ${focusDim} ${heatOn ? "heat-on" : ""}"
               style="${heatOn ? `--heat:${heatPct}` : ""}" data-node="${step.id}" transform="translate(${pos.x},${pos.y})"
               @mousedown="${(e: MouseEvent) => this.onNodeMouseDown(e, step.id)}"
               @click="${(e: MouseEvent) => this.onNodeClick(e, step.id)}"
               @mouseenter="${() => this.onNodeHover(step.id)}"
               @mouseleave="${() => this.onNodeHover(null)}">
                ${pulse}
                <g class="node-inner" data-inner="${step.id}">${shape}</g>
                ${badge}
                ${done}
            </g>
        `;
    }

    /** Hover detail only makes sense in monitoring view; ignore hovers on the plain editor. */
    private onNodeHover(id: string | null) {
        if (id !== null && !this.hasStateOverlay()) return;
        this.hoverId = id;
    }

    /**
     * The diagnostic hover card for a monitored step: the consolidated "why it is here", the last
     * error, and the detail (retries, awaited message/key, deadlines, worker, variables) an operator
     * needs to answer it without opening the code. Positioned in screen space so it stays legible at
     * any zoom, and pointer-transparent so it never eats the pan/hover it floats over.
     */
    private renderOverlayTooltip() {
        const id = this.hoverId;
        if (!id || !this.hasStateOverlay()) return nothing;
        const ov = this.overlayData[id];
        const step = this.wf.steps.find(s => s.id === id);
        const pos = this.positions[id];
        if (!ov || !pos) return nothing;
        const {h} = sizeOf(step?.type ?? "ACTION");
        const left = this.panX + pos.x * this.zoomK;
        const top = this.panY + (pos.y + h) * this.zoomK + 8;
        const fmt = (s?: string) => s ? s.replace("T", " ").slice(0, 16) : "";
        const chip = ov.state
            ? html`<span class="tip-chip tip-${ov.state.toLowerCase()}">${ov.state}</span>` : nothing;
        const row = (k: string, v?: string | null) => v == null || v === ""
            ? nothing : html`<div class="tip-row"><span class="tip-k">${k}</span><span class="tip-v">${v}</span></div>`;
        const attempt = ov.attempt != null
            ? (ov.maxRetries ? `${ov.attempt}/${ov.maxRetries}` : `${ov.attempt}`) : null;
        return html`
            <div class="ov-tip" style="left:${left}px; top:${top}px;">
                <div class="tip-head"><span class="tip-name">${step?.name ?? id}</span>${chip}</div>
                ${ov.reason ? html`<div class="tip-reason">${ov.reason}</div>` : nothing}
                ${ov.error ? html`<div class="tip-errmsg">${ov.error}</div>` : nothing}
                ${row("Attempt", attempt)}
                ${row("Awaiting", ov.awaitingMessage)}
                ${row("Key", ov.correlationKey)}
                ${row("Due", fmt(ov.deadlineAt))}
                ${row("Started", fmt(ov.startedAt))}
                ${row("Worker", ov.worker)}
                ${ov.variables && ov.variables.length ? html`
                    <div class="tip-vars">
                        ${ov.variables.map(v => html`
                            <div class="tip-row"><span class="tip-k">${v.name}</span><span class="tip-v">${v.value}</span></div>`)}
                    </div>` : nothing}
            </div>`;
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
                    <button class="close-btn" title="Close properties"
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
                        .value="${step.description ?? ""}"
                        @change="${ro ? nothing : (e: Event) => this.updateStep(step.id, {description: (e.target as HTMLTextAreaElement).value})}"></textarea>`)}
                    ${step.type === "JOIN" ? field("Join type", html`
                        <select class="inp" ?disabled="${ro}"
                                @change="${ro ? nothing : (e: Event) => this.updateStep(step.id, {joinType: (e.target as HTMLSelectElement).value as "AND" | "XOR"})}">
                            <option value="AND" ?selected="${(step.joinType ?? "AND") === "AND"}">AND — wait for all</option>
                            <option value="XOR" ?selected="${step.joinType === "XOR"}">XOR — any one</option>
                        </select>`) : nothing}
                    ${field("Preconditions (all must complete)", html`
                        <div class="checklist">
                            ${others.length === 0 ? html`<span class="check-empty">no other steps</span>`
                                : others.map(s => html`
                                <label class="check">
                                    <input type="checkbox" ?disabled="${ro}"
                                           ?checked="${preconditionsOf(step).includes(s.id)}"
                                           @change="${ro ? nothing : (e: Event) => this.togglePrecondition(step, s.id, (e.target as HTMLInputElement).checked)}"/>
                                    <span>${s.name} <em>(${s.id})</em></span>
                                </label>
                                ${preconditionsOf(step).includes(s.id) ? html`
                                    <!-- The condition belongs to this link, not to the step: it
                                         says when arriving from THIS step counts. -->
                                    <input class="inp link-guard" ?readonly="${ro}"
                                           placeholder="only when… (JEXL, optional)"
                                           title="Condition on the link from ${s.name}"
                                           .value="${guardOf(step, s.id) ?? ""}"
                                           @change="${ro ? nothing : (e: Event) => this.setGuard(step, s.id, (e.target as HTMLInputElement).value)}"/>`
                                    : nothing}`)}
                        </div>`)}
                    ${field("Step condition (gates the step however it is reached)", html`
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

    // ── Styles ────────────────────────────────────────────────────────────────

    static styles = [neutralButtonStyles, css`
        :host {
            /* Fill all the space the container offers: height:100% for a block/sized parent,
               flex:1 to grow inside a flex column/row (a Mateu zone), and align-self:stretch to
               fill the cross axis — falling back to a sensible minimum when there is no height. */
            display: block; height: 100%; min-height: 230px; box-sizing: border-box;
            flex: 1 1 auto; align-self: stretch;
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
            --ec-hover: #f1f5f9;
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
            --ec-hover: var(--lumo-contrast-10pct, #2a2e34);
        }

        .root {display: flex; flex-direction: column; height: 100%; position: relative; background: var(--ec-surface);}
        /* focusable for the Delete key, but a focus ring around the whole editor is just noise */
        .root:focus, .root:focus-visible {outline: none;}

        .root.fullscreen {
            position: fixed; inset: 0; height: 100vh; width: 100vw; z-index: 9999;
            box-shadow: 0 0 0 100vmax rgba(0, 0, 0, .15);
        }

        /* floating view/animation controls — bottom-left, clear of toolbar + minimap */
        .viewbar {
            position: absolute; left: 10px; bottom: 10px; z-index: 6;
            display: flex; align-items: center; gap: 4px; padding: 4px 6px;
            border: 1px solid var(--ec-border); border-radius: 9px;
            background: color-mix(in srgb, var(--ec-surface) 88%, transparent);
            box-shadow: 0 2px 8px #0000001a; backdrop-filter: blur(2px);
        }
        .viewbar .vbtn {
            width: 28px; height: 28px; display: flex; align-items: center; justify-content: center;
            border: none; border-radius: 6px; background: transparent; color: var(--ec-text-dim);
            cursor: pointer; font-size: 14px; line-height: 1;
        }
        .viewbar .vbtn:hover {background: var(--ec-hover); color: var(--ec-text);}
        .viewbar .vbtn svg {width: 16px; height: 16px;}
        .viewbar .vspeed {
            width: 92px; height: 4px; margin: 0 2px; cursor: pointer; accent-color: var(--ec-primary);
        }
        .viewbar .vbtn.on {background: color-mix(in srgb, #dc2626 20%, transparent); color: #dc2626;}
        .viewbar .vlabel {
            font-size: 11px; color: var(--ec-text-dim); min-width: 26px; text-align: right;
            font-variant-numeric: tabular-nums;
        }

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
            border-bottom: 1px solid var(--ec-border);
        }
        .wf-name {font-weight: 600; font-size: 1rem; color: var(--ec-text);}
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
            border-bottom: 1px solid var(--ec-border);
            background: var(--ec-hover);
        }
        .meta-grid {display: grid; grid-template-columns: 120px 1fr; gap: .4rem .75rem; align-items: start;}
        .meta-grid label {font-size: .8rem; color: var(--ec-text-dim); padding-top: .3rem;}

        /* workspace */
        .workspace {display: flex; flex: 1; overflow: hidden;}
        .canvas-wrap {flex: 1; overflow: hidden; position: relative; background: var(--ec-canvas-bg);}
        .canvas {display: block; width: 100%; height: 100%; cursor: grab; touch-action: none;}
        .canvas.panning {cursor: grabbing;}
        .scene {will-change: transform;}

        /* minimap (modux-style) */
        .minimap {
            position: absolute; right: 10px; bottom: 10px; z-index: 5;
            border: 1px solid var(--ec-border); border-radius: 8px; overflow: hidden;
            background: color-mix(in srgb, var(--ec-surface) 82%, transparent);
            box-shadow: 0 2px 8px #0000001a; cursor: pointer;
            backdrop-filter: blur(2px);
        }
        .minimap svg {display: block;}
        .minimap .mini-view {
            fill: color-mix(in srgb, var(--ec-primary) 12%, transparent);
            stroke: var(--ec-primary); stroke-width: 2; vector-effect: non-scaling-stroke;
        }

        /* nodes */
        .node {cursor: grab; transition: opacity .2s;}
        .node.dim {opacity: .2;}   /* transient: not on the path the token is walking right now */
        /* sticky: outside the focused sub-graph (click / alt+click). Kept apart from .dim because
           the animation loop owns .dim and clears it whenever it stops (e.g. in a process view). */
        .node.focus-dim {opacity: .2;}
        .node-shape {filter: url(#ec-shadow); stroke-width: 1.6; transition: stroke .12s, stroke-width .12s;}
        .node-shape.ev-start {stroke-width: 1.8;}
        .node-shape.ev-end {stroke-width: 3;}
        .node:hover .node-shape, .node.sel .node-shape {stroke: var(--ec-primary) !important; stroke-width: 2.6 !important; stroke-dasharray: 0 !important;}
        .gw-plus {stroke-width: 2.2; stroke-linecap: round; fill: none; pointer-events: none;}
        .node-badge {font-size: 9.5px; fill: var(--ec-text-dim); text-transform: uppercase; letter-spacing: .05em; font-weight: 600;}
        .node-caption {font-size: 11px; font-weight: 600; fill: var(--ec-text);}
        .node-symbol {opacity: .9;}
        /* title + id sit INSIDE the always-light node card, so they stay dark in either theme */
        .node-title {font-size: 13px; font-weight: 600; fill: #1e293b;}
        .node-id {font-size: 9.5px; fill: #64748b;}
        /* radar-ping shown as a flow token passes through a node */
        .flow-pulse {fill: var(--ec-primary); pointer-events: none;}

        /* monitoring overlay (read-only): state tint, active highlight, live count badge */
        .node.ov-running   .node-shape {stroke: #d97706 !important; stroke-width: 2.4 !important;}
        .node.ov-pending   .node-shape {stroke: #64748b !important; stroke-dasharray: 4 3 !important;}
        .node.ov-completed .node-shape {stroke: #16a34a !important;}
        .node.ov-error     .node-shape {stroke: #dc2626 !important; stroke-width: 2.4 !important;}
        .node.ov-cancelled .node-shape {stroke: #94a3b8 !important; opacity: .7;}
        .node.ov-compensated .node-shape {stroke: #dc2626 !important; stroke-dasharray: 5 4 !important;}
        .node.ov-active .node-shape {stroke: var(--ec-primary) !important; stroke-width: 3 !important; filter: drop-shadow(0 0 5px color-mix(in srgb, var(--ec-primary) 60%, transparent));}
        .node.ov-active .node-inner {animation: ec-active-pulse 1.6s ease-in-out infinite;}
        @keyframes ec-active-pulse {0%,100% {opacity: 1;} 50% {opacity: .72;}}
        .ov-count circle {fill: var(--ec-primary); stroke: var(--ec-surface); stroke-width: 1.5;}
        .ov-count text {fill: #fff; font-size: 11px; font-weight: 700;}
        .ov-done circle {fill: #16a34a; stroke: var(--ec-surface); stroke-width: 2;}
        .ov-done .ov-check {fill: none; stroke: #fff; stroke-width: 2.6; stroke-linecap: round; stroke-linejoin: round;}
        /* parts the process hasn't reached yet fade back */
        .node.mon-dim {opacity: .3;}
        .edge.mon-dim, .comp-edge.mon-dim {opacity: .18;}

        /* stopped/waiting heatmap (definition view): --heat is 0–100, set per node. The fill mix is
           capped so even the hottest card keeps its dark title/id legible; the stroke goes fully warm
           to draw the eye to the hot spots. Cold (0) nodes fall back to the plain surface. */
        .node.heat-on .node-shape {
            fill: color-mix(in srgb, #ef4444 calc(var(--heat, 0) * 0.55%), var(--ec-surface)) !important;
            stroke: color-mix(in srgb, #b91c1c calc(var(--heat, 0) * 1%), var(--ec-border)) !important;
            stroke-width: calc(1.4px + var(--heat, 0) * 0.012px) !important;
        }
        .node.heat-on .ov-count circle {fill: #b91c1c;}

        /* diagnostic hover card (monitoring view): "why is this step here?" without opening code */
        .ov-tip {
            position: absolute; z-index: 30; pointer-events: none;
            min-width: 200px; max-width: 300px;
            background: var(--ec-surface, #fff); color: var(--ec-text, #1e293b);
            border: 1px solid var(--ec-border, #e2e8f0); border-radius: 8px;
            box-shadow: 0 6px 20px rgba(15, 23, 42, .18);
            padding: 8px 10px; font-size: 12px; line-height: 1.45;
        }
        .tip-head {display: flex; align-items: center; gap: 6px; margin-bottom: 4px;}
        .tip-name {font-weight: 700; font-size: 12.5px;}
        .tip-chip {
            margin-left: auto; font-size: 9.5px; font-weight: 700; text-transform: uppercase;
            letter-spacing: .04em; padding: 1px 6px; border-radius: 9px; color: #fff;
        }
        .tip-running {background: #d97706;}
        .tip-pending {background: #64748b;}
        .tip-completed {background: #16a34a;}
        .tip-error {background: #dc2626;}
        .tip-cancelled {background: #94a3b8;}
        .tip-compensated {background: #dc2626;}
        .tip-reason {font-weight: 600; margin-bottom: 4px;}
        .tip-errmsg {color: #dc2626; margin-bottom: 4px; white-space: pre-wrap; word-break: break-word;}
        .tip-row {display: flex; gap: 8px; justify-content: space-between;}
        .tip-k {color: var(--ec-text-dim, #64748b);}
        .tip-v {font-weight: 600; text-align: right; word-break: break-word;}
        .tip-vars {
            margin-top: 5px; padding-top: 5px; border-top: 1px solid var(--ec-border, #e2e8f0);
            max-height: 140px; overflow: auto;
        }

        /* a failing node shakes like an earthquake while its red ping is fresh */
        .node-inner {transform-box: fill-box; transform-origin: center;}
        .node.err .node-inner {animation: ec-shake .5s cubic-bezier(.36,.07,.19,.97) both;}
        @keyframes ec-shake {
            0%, 100% {transform: translate(0, 0) rotate(0);}
            10% {transform: translate(-5px, 1px) rotate(-2.5deg);}
            20% {transform: translate(5px, -1px) rotate(2.5deg);}
            35% {transform: translate(-4px, 1px) rotate(-2deg);}
            50% {transform: translate(4px, -1px) rotate(2deg);}
            65% {transform: translate(-3px, 0) rotate(-1.2deg);}
            80% {transform: translate(2px, 0) rotate(.8deg);}
            92% {transform: translate(-1px, 0) rotate(-.4deg);}
        }

        /* edges */
        .edge {fill: none; stroke: var(--ec-edge); stroke-width: 1.6; stroke-linejoin: round; transition: opacity .2s, stroke .2s, stroke-width .2s;}
        .edge.dim, .edge.focus-dim {opacity: .22;}                   /* not on the active path */
        .edge.active {stroke: var(--ec-primary); stroke-width: 2.4;} /* the path being animated */
        /* the selected connection: thick enough to be obvious that Delete will take THIS */
        .edge.sel, .comp-edge.sel {
            stroke: var(--ec-primary); stroke-width: 3; opacity: 1;
            filter: drop-shadow(0 0 4px color-mix(in srgb, var(--ec-primary) 60%, transparent));
        }
        /* invisible, wide, and the only thing on an edge that takes a pointer */
        .edge-hit {
            fill: none; stroke: transparent; stroke-width: 14; stroke-linejoin: round;
            cursor: pointer; pointer-events: stroke;
        }
        /* compensation associations (BPMN): red dashed */
        .comp-edge {fill: none; stroke: #dc2626; stroke-width: 1.6; stroke-dasharray: 6 5; stroke-linejoin: round; transition: opacity .2s, stroke-width .2s;}
        .comp-edge.dim, .comp-edge.focus-dim {opacity: .18;}
        .comp-edge.active {stroke-width: 2.6;}  /* the error path — stays red, just bolder */
        /* the single animated token walking the current path */
        .flow-token {fill: var(--ec-primary); pointer-events: none; filter: drop-shadow(0 0 3px var(--ec-primary));}

        /* drawing a new precondition line (ctrl+drag) */
        .link-draft {stroke: var(--ec-primary); stroke-width: 2; stroke-dasharray: 5 4; fill: none; pointer-events: none;}
        .node.link-source .node-shape {stroke: var(--ec-primary) !important;}
        .node.link-target .node-shape {
            stroke: var(--ec-primary) !important; stroke-width: 3 !important; stroke-dasharray: 0 !important;
            filter: drop-shadow(0 0 6px color-mix(in srgb, var(--ec-primary) 70%, transparent));
        }
        .node.link-target {cursor: alias;}

        /* precondition guard chips on edges */
        .guard {pointer-events: none; transition: opacity .2s;}
        .guard.dim, .guard.focus-dim {opacity: .15;}   /* its edge is not in the focus */
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
            border-left: 1px solid var(--ec-border);
            display: flex; flex-direction: column;
            background: var(--ec-surface);
        }
        .prop-header {
            display: flex; align-items: center; gap: .4rem;
            padding: .6rem .75rem; font-size: .85rem; font-weight: 600;
            border-bottom: 1px solid var(--ec-border);
        }
        .prop-header span {flex: 1;}
        /* Both were thin glyphs floating in the header and read as decoration; sized up and given
           a hover surface so they read as the buttons they are. */
        .link-guard {margin: .1rem 0 .35rem 1.35rem; width: calc(100% - 1.35rem); font-size: .72rem;}
        .del-btn, .close-btn {
            background: none; border: none; cursor: pointer; color: var(--ec-text-dim);
            font-size: 1.05rem; padding: .15rem .4rem; border-radius: 5px; line-height: 1;
        }
        .close-btn:hover {color: var(--ec-text);}
        .del-btn:hover {color: #dc2626;}
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
            font-size: .82rem; color: var(--ec-text); background: var(--ec-surface);
            outline: none; font-family: inherit; transition: border-color .15s;
        }
        .inp:focus {border-color: var(--ec-primary);}
        textarea.inp {resize: vertical;}
        input[readonly].inp {background: var(--ec-hover); color: var(--ec-text-faint);}

        /* precondition checklist */
        .checklist {
            display: flex; flex-direction: column; gap: .15rem;
            max-height: 140px; overflow-y: auto;
            border: 1px solid var(--ec-border); border-radius: 6px; padding: .35rem .5rem;
            background: var(--ec-surface);
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

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
    START:            {fill: "#ffffff", stroke: "#64748b", symbol: "flow"},
    ACTION:           {fill: "#ffffff", stroke: "#6d28d9", symbol: "process"},
    USER_TASK:        {fill: "#fef9c3", stroke: "#ca8a04", symbol: "person"},
    RULE:             {fill: "#ffffff", stroke: "#4f46e5", symbol: "operation"},
    TIMER:            {fill: "#ffffff", stroke: "#d97706", symbol: "clock"},
    WAIT_FOR_MESSAGE: {fill: "#ffffff", stroke: "#0891b2", symbol: "event"},
    SEND_MESSAGE:     {fill: "#ffffff", stroke: "#0891b2", symbol: "flow"},
    FORK:             {fill: "#f5f3ff", stroke: "#6d28d9", symbol: "flow", dashed: true},
    JOIN:             {fill: "#f5f3ff", stroke: "#6d28d9", symbol: "flow", dashed: true},
    PROCESS:          {fill: "#eef2ff", stroke: "#4f46e5", symbol: "component"},
    END:              {fill: "#dcfce7", stroke: "#16a34a", symbol: "event"},
};
const DEFAULT_STYLE: NodeStyle = {fill: "#ffffff", stroke: "#94a3b8", symbol: "process"};
const styleOf = (t: StepType): NodeStyle => NODE_STYLE[t] ?? DEFAULT_STYLE;

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
            } catch {
                /* keep previous */
            }
        }
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
            children: steps.map(s => ({
                id: s.id,
                width: NODE_W,
                height: NODE_H,
            })),
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
        const pts = Object.values(this.positions);
        const w = pts.length ? Math.max(...pts.map(p => p.x)) + NODE_W + PAD : 600;
        const h = pts.length ? Math.max(...pts.map(p => p.y)) + NODE_H + PAD : 400;
        return {w: Math.max(w, 600), h: Math.max(h, 400)};
    }

    private boxOf(pos: NodePos): Box {
        return {x: pos.x + NODE_W / 2, y: pos.y + NODE_H / 2, w: NODE_W, h: NODE_H};
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
                             @click="${(e: MouseEvent) => {if (e.target === e.currentTarget) this.selectedId = null;}}">
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
                            ${steps.map(s => this.renderArrows(s))}
                            ${steps.map(s => this.renderNode(s))}
                            ${steps.map(s => this.renderGuard(s))}
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

    private renderArrows(step: WorkflowStep) {
        const to = this.positions[step.id];
        if (!to) return svg``;
        const tBox = this.boxOf(to);
        const preconditions = preconditionsOf(step);
        const n = preconditions.length;

        return preconditions.map((fromId, i) => {
            const from = this.positions[fromId];
            if (!from) return svg``;
            // Orthogonal route, with several edges into the same node spread apart so they stay
            // distinguishable (echoing modux's parallel-edge handling).
            const spread = n <= 1 ? 0 : (i - (n - 1) / 2) * 11;
            const pts = orthogonalRoute(this.boxOf(from), tBox, spread);
            return svg`<path class="edge" d="${roundedPath(pts)}" marker-end="url(#ec-arrow)"/>`;
        });
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
        const from = this.positions[preconditions[0]];
        if (!from) return svg``;

        // Sit toward the source end of the edge, clear of the target node's badge.
        const mid = polylinePointAt(orthogonalRoute(this.boxOf(from), this.boxOf(to), 0), 0.38);
        const text = expr.length > 30 ? expr.slice(0, 29) + "…" : expr;
        const w = Math.max(30, text.length * 6.3 + 22);
        const h = 19;
        return svg`
            <g class="guard" transform="translate(${mid.x}, ${mid.y})">
                <rect x="${-w / 2}" y="${-h / 2}" width="${w}" height="${h}" rx="9.5"/>
                <text x="0" y="3.6" text-anchor="middle">◇ ${text}</text>
            </g>
        `;
    }

    private renderNode(step: WorkflowStep) {
        const pos = this.positions[step.id] ?? {x: PAD, y: PAD};
        const st = styleOf(step.type);
        const selected = this.selectedId === step.id;
        const label = step.name.length > 22 ? step.name.slice(0, 21) + "…" : step.name;
        const badge = badgeOf(step);
        const badgeText = badge.length > 26 ? badge.slice(0, 25) + "…" : badge;

        return svg`
            <g class="node ${selected ? "sel" : ""}" transform="translate(${pos.x},${pos.y})"
               @mousedown="${(e: MouseEvent) => this.onNodeMouseDown(e, step.id)}"
               @click="${(e: MouseEvent) => {e.stopPropagation(); this.selectedId = step.id;}}">
                <text class="node-badge" x="2" y="-7">${badgeText}</text>
                <rect class="node-card" width="${NODE_W}" height="${NODE_H}" rx="10"
                      fill="${st.fill}" stroke="${st.stroke}" stroke-width="1.4"
                      stroke-dasharray="${st.dashed ? "6 4" : "0"}"/>
                <g class="node-symbol" transform="translate(${NODE_W - 23}, 9)"
                   fill="none" stroke="${st.stroke}" stroke-width="1.1"
                   stroke-linejoin="round">${SYMBOLS[st.symbol] ?? svg``}</g>
                <text class="node-title" x="14" y="${NODE_H / 2 - 2}">${label}</text>
                <text class="node-id" x="14" y="${NODE_H / 2 + 14}">${step.id}</text>
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
            display: block; height: 230px; font-family: var(--lumo-font-family, sans-serif);
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
        .node {cursor: grab;}
        .node-card {filter: url(#ec-shadow); transition: stroke .12s, stroke-width .12s;}
        .node:hover .node-card, .node.sel .node-card {stroke: var(--ec-primary) !important; stroke-width: 2.4 !important; stroke-dasharray: 0 !important;}
        .node-badge {font-size: 9.5px; fill: var(--ec-text-dim); text-transform: uppercase; letter-spacing: .05em; font-weight: 600;}
        .node-symbol {opacity: .9;}
        .node-title {font-size: 13px; font-weight: 600; fill: var(--ec-text);}
        .node-id {font-size: 9.5px; fill: var(--ec-text-faint);}

        /* edges */
        .edge {fill: none; stroke: var(--ec-edge); stroke-width: 1.6; stroke-linejoin: round;}

        /* precondition guard chips on edges */
        .guard {pointer-events: none;}
        .guard rect {fill: var(--ec-surface); stroke: var(--ec-border); stroke-width: 1;}
        .guard text {
            font-size: 10.5px; fill: var(--ec-text-dim);
            font-family: var(--lumo-font-family-monospace, ui-monospace, monospace);
        }

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

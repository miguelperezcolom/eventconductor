import { customElement, property, state } from "lit/decorators.js";
import { css, html, LitElement, nothing } from "lit";
import { neutralButtonStyles, iconPlus, iconClose, iconUp, iconDown } from "./neutralChrome";

// ── Domain types ─────────────────────────────────────────────────────────────

/**
 * Field data types, kept in sync with the engine's form schema
 * (modules/forms-engine/src/main/resources/form-schema.json → $defs/Field/properties/dataType).
 */
const DATA_TYPES = [
    "integer", "string", "number", "date", "time", "dateTime", "bool", "array", "file",
    "status", "money", "component", "menu", "range", "action", "actionGroup", "dateRange",
] as const;
type DataType = typeof DATA_TYPES[number];

/**
 * Visual / input stereotypes, kept in sync with the engine's form schema
 * (…/form-schema.json → $defs/Field/properties/stereotype). Default is "regular".
 */
const STEREOTYPES = [
    "regular", "radio", "checkbox", "textarea", "toggle", "combobox", "select", "email",
    "password", "richText", "listBox", "html", "markdown", "image", "icon", "link", "money",
    "grid", "color", "choice", "popover", "slider", "button", "stars",
] as const;
type Stereotype = typeof STEREOTYPES[number];
const DEFAULT_STEREOTYPE: Stereotype = "regular";

interface FormField {
    id: string;
    label: string;
    dataType: DataType;
    stereotype?: Stereotype | null;
    required?: boolean | null;
    description?: string | null;
}

interface FormDefinition {
    id?: string | null;
    name: string;
    description?: string | null;
    fields: FormField[];
}

const EMPTY_FORM: FormDefinition = { name: "New Form", fields: [] };

/** A blank field with a fresh id unique within the current set. */
function newField(existing: FormField[]): FormField {
    let n = existing.length + 1;
    const ids = new Set(existing.map(f => f.id));
    while (ids.has("field" + n)) n++;
    return { id: "field" + n, label: "Field " + n, dataType: "string", stereotype: "regular", required: false };
}

// ── Component ─────────────────────────────────────────────────────────────────

@customElement("eventconductor-form-editor")
export class EventConductorFormEditor extends LitElement {

    /** JSON string of the FormDefinition. */
    @property() value = '{"name":"New Form","fields":[]}';

    /** When true, all editing interactions are disabled — the editor becomes a read-only view. */
    @property({ type: Boolean }) readOnly = false;

    /**
     * Drops the expand button. Inside an IDE editor pane the component already fills everything it
     * is allowed to fill, so "expand" either does nothing visible or fights the host's own layout.
     * The app, where the editor sits in a page among other things, leaves it on. Mirrors the graph.
     */
    @property({ type: Boolean, attribute: "no-expand" }) noExpand = false;

    /** Reflected so `:host([dark])` maps the theme onto the host's Lumo dark palette. */
    @property({ type: Boolean, reflect: true }) dark = false;

    @state() private form: FormDefinition = { name: "New Form", fields: [] };
    /** Which field row is expanded for editing (its id), or null when the list is collapsed. */
    @state() private editingId: string | null = null;
    /** Split view: show the live preview beside the editor. Off in a very narrow host. */
    @state() private showPreview = true;
    @state() private fullscreen = false;

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    updated(changed: Map<string, unknown>) {
        if (changed.has("value")) {
            try {
                const parsed = JSON.parse(this.value) as FormDefinition;
                this.form = normalise(parsed);
            } catch {
                /* keep previous — a transient invalid value must not blank the editor */
            }
        }
    }

    // ── Mutation helpers ──────────────────────────────────────────────────────

    /** Serialise the current form back out in the schema's JSON shape and notify the host. */
    private emit() {
        const json = JSON.stringify(serialise(this.form), null, 2);
        this.dispatchEvent(new CustomEvent("value-changed", { detail: { value: json }, bubbles: true, composed: true }));
    }

    private updateForm(patch: Partial<FormDefinition>) {
        this.form = { ...this.form, ...patch };
        this.emit();
    }

    private updateField(index: number, patch: Partial<FormField>) {
        const fields = this.form.fields.map((f, i) => (i === index ? { ...f, ...patch } : f));
        this.form = { ...this.form, fields };
        this.emit();
    }

    private addField() {
        if (this.readOnly) return;
        const field = newField(this.form.fields);
        this.form = { ...this.form, fields: [...this.form.fields, field] };
        this.editingId = field.id;
        this.emit();
    }

    private removeField(index: number) {
        if (this.readOnly) return;
        const removed = this.form.fields[index];
        this.form = { ...this.form, fields: this.form.fields.filter((_, i) => i !== index) };
        if (this.editingId === removed?.id) this.editingId = null;
        this.emit();
    }

    private moveField(index: number, delta: number) {
        if (this.readOnly) return;
        const target = index + delta;
        if (target < 0 || target >= this.form.fields.length) return;
        const fields = [...this.form.fields];
        const [f] = fields.splice(index, 1);
        fields.splice(target, 0, f);
        this.form = { ...this.form, fields };
        this.emit();
    }

    private toggleEditing(id: string) {
        this.editingId = this.editingId === id ? null : id;
    }

    private toggleFullscreen() {
        if (this.fullscreen) {
            if (document.fullscreenElement === this) document.exitFullscreen();
        } else {
            this.requestFullscreen?.().catch(() => { /* not allowed here — leave inline */ });
        }
    }

    connectedCallback() {
        super.connectedCallback();
        this.fsHandler = () => { this.fullscreen = document.fullscreenElement === this; };
        document.addEventListener("fullscreenchange", this.fsHandler);
    }

    disconnectedCallback() {
        super.disconnectedCallback();
        if (this.fsHandler) document.removeEventListener("fullscreenchange", this.fsHandler);
    }

    private fsHandler?: () => void;

    // ── Render ────────────────────────────────────────────────────────────────

    render() {
        const ro = this.readOnly;
        return html`
            <div class="root ${this.fullscreen ? "fullscreen" : ""}">
                <div class="viewbar">
                    <span class="title">Form editor</span>
                    <span class="spacer"></span>
                    <button class="vbtn" @click="${() => (this.showPreview = !this.showPreview)}"
                            title="${this.showPreview ? "Hide preview" : "Show preview"}">
                        ${this.showPreview ? "Hide preview" : "Show preview"}
                    </button>
                    ${this.noExpand ? nothing : html`
                        <button class="vbtn" @click="${() => this.toggleFullscreen()}"
                                title="${this.fullscreen ? "Exit full screen" : "Full screen"}">
                            ${this.fullscreen ? "Exit" : "Expand"}
                        </button>`}
                </div>
                <div class="body ${this.showPreview ? "split" : ""}">
                    <div class="editor">
                        ${this.renderFormMeta(ro)}
                        ${this.renderFieldList(ro)}
                    </div>
                    ${this.showPreview ? html`<div class="preview">${this.renderPreview()}</div>` : nothing}
                </div>
            </div>`;
    }

    private renderFormMeta(ro: boolean) {
        return html`
            <div class="section">
                <label class="lbl">Name</label>
                <input class="inp" ?readonly="${ro}" .value="${this.form.name ?? ""}"
                       @input="${(e: Event) => this.updateForm({ name: (e.target as HTMLInputElement).value })}"/>
                <label class="lbl">Description</label>
                <textarea class="inp" rows="2" ?readonly="${ro}" .value="${this.form.description ?? ""}"
                          @input="${(e: Event) => this.updateForm({ description: (e.target as HTMLTextAreaElement).value })}"></textarea>
            </div>`;
    }

    private renderFieldList(ro: boolean) {
        return html`
            <div class="section">
                <div class="section-head">
                    <span class="lbl">Fields (${this.form.fields.length})</span>
                    ${ro ? nothing : html`
                        <button class="nbtn primary" @click="${() => this.addField()}">
                            ${iconPlus} Add field
                        </button>`}
                </div>
                ${this.form.fields.length === 0
                    ? html`<div class="empty">No fields yet.${ro ? "" : " Use “Add field” to start."}</div>`
                    : this.form.fields.map((f, i) => this.renderFieldRow(f, i, ro))}
            </div>`;
    }

    private renderFieldRow(f: FormField, i: number, ro: boolean) {
        const open = this.editingId === f.id;
        return html`
            <div class="field-row ${open ? "open" : ""}">
                <div class="field-head" @click="${() => this.toggleEditing(f.id)}">
                    <span class="field-caret">${open ? "▾" : "▸"}</span>
                    <span class="field-name">${f.label || f.id}</span>
                    <span class="field-meta">${f.dataType}${f.stereotype && f.stereotype !== DEFAULT_STEREOTYPE ? " · " + f.stereotype : ""}${f.required ? " · required" : ""}</span>
                    <span class="spacer"></span>
                    ${ro ? nothing : html`
                        <button class="icon-btn" title="Move up" ?disabled="${i === 0}"
                                @click="${(e: Event) => { e.stopPropagation(); this.moveField(i, -1); }}">${iconUp}</button>
                        <button class="icon-btn" title="Move down" ?disabled="${i === this.form.fields.length - 1}"
                                @click="${(e: Event) => { e.stopPropagation(); this.moveField(i, 1); }}">${iconDown}</button>
                        <button class="icon-btn danger" title="Remove"
                                @click="${(e: Event) => { e.stopPropagation(); this.removeField(i); }}">${iconClose}</button>`}
                </div>
                ${open ? this.renderFieldEditor(f, i, ro) : nothing}
            </div>`;
    }

    private renderFieldEditor(f: FormField, i: number, ro: boolean) {
        return html`
            <div class="field-body">
                <div class="grid2">
                    <div>
                        <label class="lbl">ID</label>
                        <input class="inp" ?readonly="${ro}" .value="${f.id}"
                               @input="${(e: Event) => this.updateField(i, { id: (e.target as HTMLInputElement).value })}"/>
                    </div>
                    <div>
                        <label class="lbl">Label</label>
                        <input class="inp" ?readonly="${ro}" .value="${f.label}"
                               @input="${(e: Event) => this.updateField(i, { label: (e.target as HTMLInputElement).value })}"/>
                    </div>
                    <div>
                        <label class="lbl">Data type</label>
                        <select class="inp" ?disabled="${ro}" .value="${f.dataType}"
                                @change="${(e: Event) => this.updateField(i, { dataType: (e.target as HTMLSelectElement).value as DataType })}">
                            ${DATA_TYPES.map(t => html`<option value="${t}" ?selected="${t === f.dataType}">${t}</option>`)}
                        </select>
                    </div>
                    <div>
                        <label class="lbl">Stereotype</label>
                        <select class="inp" ?disabled="${ro}" .value="${f.stereotype ?? DEFAULT_STEREOTYPE}"
                                @change="${(e: Event) => this.updateField(i, { stereotype: (e.target as HTMLSelectElement).value as Stereotype })}">
                            ${STEREOTYPES.map(s => html`<option value="${s}" ?selected="${s === (f.stereotype ?? DEFAULT_STEREOTYPE)}">${s}</option>`)}
                        </select>
                    </div>
                </div>
                <label class="checkline">
                    <input type="checkbox" ?disabled="${ro}" .checked="${!!f.required}"
                           @change="${(e: Event) => this.updateField(i, { required: (e.target as HTMLInputElement).checked })}"/>
                    Required
                </label>
                <label class="lbl">Description</label>
                <textarea class="inp" rows="2" ?readonly="${ro}" .value="${f.description ?? ""}"
                          @input="${(e: Event) => this.updateField(i, { description: (e.target as HTMLTextAreaElement).value })}"></textarea>
            </div>`;
    }

    // ── Live preview ───────────────────────────────────────────────────────────

    /** A faithful "what the user will see" render of the form as real (inert) inputs. */
    private renderPreview() {
        return html`
            <div class="preview-card">
                <div class="preview-title">${this.form.name || "Untitled form"}</div>
                ${this.form.description ? html`<div class="preview-desc">${this.form.description}</div>` : nothing}
                ${this.form.fields.length === 0
                    ? html`<div class="empty">Add fields to see the form preview.</div>`
                    : this.form.fields.map(f => this.renderPreviewField(f))}
            </div>`;
    }

    private renderPreviewField(f: FormField) {
        const label = html`<label class="pv-label">${f.label || f.id}${f.required ? html`<span class="pv-req">*</span>` : nothing}</label>`;
        const control = this.renderPreviewControl(f);
        return html`
            <div class="pv-field">
                ${label}
                ${control}
                ${f.description ? html`<div class="pv-hint">${f.description}</div>` : nothing}
            </div>`;
    }

    /** Maps dataType + stereotype to the closest real input, so the preview reads like the form. */
    private renderPreviewControl(f: FormField) {
        const s = f.stereotype ?? DEFAULT_STEREOTYPE;
        const ph = f.label || f.id;
        // Boolean-ish first: bool + toggle/checkbox render as a checkbox.
        if (f.dataType === "bool" || s === "checkbox" || s === "toggle") {
            return html`<input class="pv-check" type="checkbox" disabled/>`;
        }
        if (s === "textarea" || s === "richText" || s === "html" || s === "markdown" || f.dataType === "component") {
            return html`<textarea class="pv-inp" rows="3" disabled placeholder="${ph}"></textarea>`;
        }
        if (s === "select" || s === "combobox" || s === "listBox" || s === "choice" || s === "menu"
            || f.dataType === "status" || f.dataType === "menu") {
            return html`<select class="pv-inp" disabled><option>Select…</option></select>`;
        }
        if (s === "radio") {
            return html`<div class="pv-radio"><label><input type="radio" disabled/> Option A</label><label><input type="radio" disabled/> Option B</label></div>`;
        }
        if (s === "slider" || s === "range" || f.dataType === "range") {
            return html`<input class="pv-inp" type="range" disabled/>`;
        }
        if (s === "color" || f.dataType === "status" && s === "color") {
            return html`<input class="pv-inp" type="color" disabled/>`;
        }
        if (s === "button" || f.dataType === "action" || f.dataType === "actionGroup") {
            return html`<button class="pv-inp pv-btn" disabled>${ph}</button>`;
        }
        if (s === "stars") {
            return html`<div class="pv-stars">★★★☆☆</div>`;
        }
        if (f.dataType === "file" || s === "image") {
            return html`<input class="pv-inp" type="file" disabled/>`;
        }
        // Native input types by dataType, honouring email/password stereotypes.
        const type = f.dataType === "integer" || f.dataType === "number" || f.dataType === "money" ? "number"
            : f.dataType === "date" ? "date"
            : f.dataType === "time" ? "time"
            : f.dataType === "dateTime" ? "datetime-local"
            : f.dataType === "dateRange" ? "date"
            : s === "email" ? "email"
            : s === "password" ? "password"
            : s === "link" ? "url"
            : "text";
        return html`<input class="pv-inp" type="${type}" disabled placeholder="${ph}"/>`;
    }

    // ── Styles ────────────────────────────────────────────────────────────────

    static styles = [neutralButtonStyles, css`
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
}

// ── (de)serialisation ──────────────────────────────────────────────────────────

/** Fill in the shape the editor works with from a possibly-partial parsed value. */
function normalise(parsed: FormDefinition | null): FormDefinition {
    if (!parsed || typeof parsed !== "object") return { ...EMPTY_FORM };
    return {
        id: parsed.id ?? undefined,
        name: parsed.name ?? "New Form",
        description: parsed.description ?? undefined,
        fields: Array.isArray(parsed.fields) ? parsed.fields.map(normaliseField) : [],
    };
}

function normaliseField(f: FormField): FormField {
    return {
        id: f?.id ?? "",
        label: f?.label ?? "",
        dataType: (DATA_TYPES as readonly string[]).includes(f?.dataType) ? f.dataType : "string",
        stereotype: f?.stereotype ?? undefined,
        required: f?.required ?? undefined,
        description: f?.description ?? undefined,
    };
}

/** Back to the schema's JSON shape: drop the editor's undefined/empty optionals cleanly. */
function serialise(form: FormDefinition): FormDefinition {
    const out: FormDefinition = { name: form.name ?? "", fields: (form.fields ?? []).map(serialiseField) };
    if (form.id) out.id = form.id;
    if (form.description != null && form.description !== "") out.description = form.description;
    return out;
}

function serialiseField(f: FormField): FormField {
    const out: FormField = { id: f.id ?? "", label: f.label ?? "", dataType: f.dataType ?? "string" };
    if (f.stereotype != null && f.stereotype !== "") out.stereotype = f.stereotype;
    if (f.required) out.required = true;
    if (f.description != null && f.description !== "") out.description = f.description;
    return out;
}

import { css, svg, TemplateResult } from "lit";

/**
 * Design-system-neutral chrome for the shared "advanced" components (the form editor here): plain
 * <button> styling plus a few inline SVG icons. Mirrors
 * modules/workflow-engine/frontend/src/neutralChrome.ts — the styles use Lumo CSS custom properties
 * WITH neutral fallbacks, so under the Vaadin renderer they pick up the theme and look native, while
 * under any other renderer they degrade to a clean neutral look.
 */
export const neutralButtonStyles = css`
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
`;

const icon = (paths: TemplateResult) => svg`
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
         stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${paths}</svg>`;

/** Plus / add. */
export const iconPlus = icon(svg`
    <line x1="12" y1="5" x2="12" y2="19"></line>
    <line x1="5" y1="12" x2="19" y2="12"></line>`);

/** Close / X (also used for "remove"). */
export const iconClose = icon(svg`
    <line x1="18" y1="6" x2="6" y2="18"></line>
    <line x1="6" y1="6" x2="18" y2="18"></line>`);

/** Chevron up — move a field earlier. */
export const iconUp = icon(svg`
    <polyline points="18 15 12 9 6 15"></polyline>`);

/** Chevron down — move a field later. */
export const iconDown = icon(svg`
    <polyline points="6 9 12 15 18 9"></polyline>`);

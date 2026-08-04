---
title: IDE Plugins (VS Code & IntelliJ)
description: Edit EventConductor .ec workflow definitions as an interactive graph or as YAML/JSON, with schema validation, in VS Code and IntelliJ IDEA.
---

EventConductor ships editor plugins for **VS Code** and **IntelliJ IDEA** that open a workflow
definition as an **interactive graph** or as plain **YAML/JSON** — both views editing the same
file. The graph is the exact same component the app renders, so authoring looks the same
everywhere.

## The `.ec` format

`.ec` is EventConductor's first-class extension for a workflow definition. Its content may be
**JSON or YAML** — the plugins (and the engine's git import) detect the format from the content
and preserve it, so you pick whichever you prefer without renaming. An `.ec` file is just a
[workflow definition](/guides/workflow-definitions/); it is validated against the same
[JSON schema](/reference/maven-plugin/). A file that is still empty — a `.ec` you have just created — is written as **YAML**, which is what the examples and this documentation use.

The engine also imports `.ec` files (alongside `.json` / `.yaml` / `.yml`) from configured Git
repositories and from `classpath:/workflows/` — see [Configuration](/reference/configuration/#git-import-workflowgit-import).

## VS Code

The extension registers a **custom editor** for `*.ec`: opening a file shows the graph by
default, backed by the underlying text document.

- **Graph editing** — drag nodes, **Shift+drag** from one node to another to draw a precondition
  line, click a node to select it and filter the graph to what it is connected to, wheel to zoom,
  drag the background to pan, plus fit-to-view and a minimap. A token-flow **simulation** animates
  the paths (and pauses on an AND-join to show it synchronising on all its branches).
- **Workflow status** — the graph's **Settings** panel declares whether the workflow is `Active`,
  `Disabled` or `Archived`, written to the `.ec` as `status`. It is a floor the runtime cannot
  lift: an operator can take a workflow out of service, but cannot enable one whose definition
  disables it.
- **Deleting** — select a node or a connection and press **Delete** (or **Backspace**). Deleting a
  node also removes every reference other steps held to it: preconditions, whether written as
  `preconditionStepId` or in a `preconditionStepIds` list, and the `compensationStepId` of a
  rollbackable step. Deleting a connection removes only that link and leaves both steps in place.
  Delete typed into a field of the step panel edits the text, as it should.
- **Graph ↔ text** — the graph is backed by the text document, so graphical edits update the file
  and text edits refresh the graph. Use the editor title button **Show YAML/JSON side-by-side**, or
  the commands **EventConductor: Open as Text** / **Open as Graph** (Command Palette).
- **Schema validation** — `.ec` is associated with the YAML language and the workflow-definition
  schema is registered for `*.ec`, so the text view gives validation and completion. Install the
  **YAML** extension by Red Hat (recommended automatically via the extension pack).
- **Theme** — the graph follows VS Code's light/dark theme.

Install from the Marketplace (search *EventConductor*), or from the `.vsix` in the
[GitHub releases](https://github.com/miguelperezcolom/eventconductor/releases): Extensions view →
`…` menu → **Install from VSIX…**.

## IntelliJ IDEA

The plugin (IntelliJ IDEA **2024.2+**) provides a **split editor** for `*.ec`: the text
(YAML/JSON) on one side and the interactive graph (embedded via JCEF) on the other. The editor's
**Editor / Split / Preview** toolbar toggles switch between text-only, graph-only, or both.

- **Two-way sync** between the graph and the document, same as VS Code.
- **Schema validation** — `.ec` is parsed as YAML (a JSON superset) and validated against the
  workflow-definition schema; the status bar shows the applied schema (**EventConductor**).
- **Theme** — the graph follows the IDE's light/dark theme.

Install from the JetBrains Marketplace (search *EventConductor*), or from the `.zip` in the
[GitHub releases](https://github.com/miguelperezcolom/eventconductor/releases): Settings → Plugins
→ ⚙ → **Install Plugin from Disk…**.

:::note
Graphical edits round-trip the definition through the model, so YAML comments and exact
formatting are not preserved when you edit in the graph — use the text view to keep them.
:::

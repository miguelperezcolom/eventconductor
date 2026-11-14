---
title: IDE Plugins (VS Code & IntelliJ)
description: Edit EventConductor workflow definitions (*.ec.yaml, .ec) as an interactive graph or as YAML/JSON, with schema validation, in VS Code and IntelliJ IDEA.
---

EventConductor ships editor plugins for **VS Code** and **IntelliJ IDEA** that open a workflow
definition as an **interactive graph** or as plain **YAML/JSON** — both views editing the same
file. The graph is the exact same component the app renders, so authoring looks the same
everywhere.

## File names: `*.ec.yaml` (recommended)

**Name definitions `*.ec.yaml`** — `hotel-booking.ec.yaml`, `order.ec.yaml` (`.ec.yml` works too).
It keeps the `.yaml` extension every tool already understands, and the `.ec` before it tells the
plugins the file is a workflow: both open it in the **graph editor** with **workflow-schema
validation**. A file that is still empty — one you have just created — is written as **YAML**, which
is what the examples and this documentation use. The file is just a
[workflow definition](/guides/workflow-definitions/#file-names), validated against the same
[JSON schema](/reference/maven-plugin/) the Maven plugin uses.

The engine imports it as the YAML it is — from `classpath:/workflows/`, directories and Git
repositories (see [Configuration](/reference/configuration/#git-import-workflowgit-import)) — and
`mvn eventconductor:validate` picks it up as a `.yaml`. The compound suffix is not part of the name:
a file that declares no `id` is imported as `order`, not `order.ec`.

Also accepted: `.ec`, `*.workflow.yaml`, and plain `.yaml` / `.yml` / `.json` — see below.

### `.ec` and `*.workflow.yaml`

`.ec` is EventConductor's own extension for a workflow definition, and stays fully supported. Its
content may be **JSON or YAML** — the plugins (and the engine's imports) detect the format from the
content and preserve it. `*.workflow.yaml` (and `.workflow.yml`) is a compound suffix like
`*.ec.yaml`, treated exactly the same way: graph editor, schema validation, and a derived id of
`order` for `order.workflow.yaml`.

A plain `order.yaml` (or `.json`) is still imported, but the plugins cannot tell it is a workflow,
so it gets neither the graph nor the schema.

## VS Code

The extension registers a **custom editor** for `*.ec.yaml` (and `*.ec` / `*.workflow.yaml`):
opening a file shows the graph by default, backed by the underlying text document.

- **Graph editing** — drag nodes, **Shift+drag** from one node to another to draw a precondition
  line, click a node to select it and filter the graph to what it is connected to, wheel to zoom,
  drag the background to pan, plus fit-to-view and a minimap. A token-flow **simulation** animates
  the paths (and pauses on an AND-join to show it synchronising on all its branches).
- **Loops and multi-instance** — a `forEach` step is drawn as a stack of cards with what it runs
  over (`∥ rooms`, `⇣` when sequential), a `repeat` step carries a `↻ until …` chip, and a
  `loopBack` is a dashed violet back-edge with its condition. The step panel edits all three
  (items, as/index, mode, max concurrency, output, completion; until, max iterations, delay; loop
  back to, while), plus `onIterationFailure` (failFast / continue / compensateAll) and the
  iteration `label`, flagging the combinations validation refuses. See
  [Loops and Multi-Instance](/guides/loops-and-multi-instance/).
- **Inline PROCESS bodies** — a PROCESS step's panel chooses between naming a child definition and
  writing it **in line** (`steps:`). **Double-click** an inline PROCESS (or **Edit ▸** in its
  panel) to edit its body with the same tools as the workflow — palette, links, panel, delete —
  in place of the graph; a breadcrumb shows where you are and **Esc** (or **↩ Back**) goes back up,
  to the arrangement and zoom you left. Bodies nest. The body is laid out automatically: positions
  dragged inside a body are not written to the file (the schema has no `layout` for a body).
- **Branch cleanup** — an XOR join's panel offers **Compensate losers** and an END's **Compensate
  abandoned** (see [Retries, Timeouts and Compensation](/guides/retries-timeouts-compensation/)).
- **Watching a run** — in the execution view, double-click a forEach to open it into its
  iterations (an inline body into one lane per iteration), and double-click a PROCESS node, an
  iteration or a lane header to follow that child run. Only the children listed on the execution
  view's current page of child processes can be opened this way; the engine's graph does not carry
  child ids.
- **Workflow status** — the graph's **Settings** panel declares whether the workflow is `Active`,
  `Disabled` or `Archived`, written to the definition file as `status`. It is a floor the runtime cannot
  lift: an operator can take a workflow out of service, but cannot enable one whose definition
  disables it.
- **Deleting** — select a node or a connection and press **Delete** (or **Backspace**). Deleting a
  node also removes every reference other steps held to it: preconditions, whether written as
  `preconditionStepId` or in a `preconditionStepIds` list, and the `compensationStepId` of a
  compensable step. Deleting a connection removes only that link and leaves both steps in place.
  Delete typed into a field of the step panel edits the text, as it should.
- **Graph ↔ text** — the graph is backed by the text document, so graphical edits update the file
  and text edits refresh the graph. Use the editor title button **Show YAML/JSON side-by-side**, or
  the commands **EventConductor: Open as Text** / **Open as Graph** (Command Palette).
- **Schema validation** — `.ec`, `.ecform`, `.ecrule` and `.ectask` are associated with the YAML
  language, and their schemas are contributed to the Red Hat **YAML** extension (`yamlValidation`)
  for those extensions and for `*.workflow.yaml` / `*.ec.yaml`, so the text view gives validation
  and completion. Install the **YAML** extension by Red Hat (recommended automatically via the
  extension pack).
- **Theme** — the graph follows VS Code's light/dark theme.

Install from the Marketplace (search *EventConductor*), or from the `.vsix` in the
[GitHub releases](https://github.com/miguelperezcolom/eventconductor/releases): Extensions view →
`…` menu → **Install from VSIX…**.

## IntelliJ IDEA

The plugin (IntelliJ IDEA **2024.2+**) provides a **split editor** for `*.ec.yaml` (and
`*.ec` / `*.workflow.yaml`): the text
(YAML/JSON) on one side and the interactive graph (embedded via JCEF) on the other. The editor's
**Editor / Split / Preview** toolbar toggles switch between text-only, graph-only, or both.

- **Two-way sync** between the graph and the document, same as VS Code.
- **Same graph editor** as VS Code, inline PROCESS bodies, `onIterationFailure`/`label` and the
  branch-cleanup flags included. The plugin has no execution view, so there are no runs to expand
  or child runs to open.
- **Schema validation** — `*.ec.yaml` (and `.ec`, parsed as YAML, a JSON superset) is validated
  against the workflow-definition schema; the status bar shows the applied schema (**EventConductor**).
  `.ec`, `.ecform`, `.ecrule` and `.ectask` have a file type of their own, **EventConductor**
  (language YAML), under *Settings → Editor → File Types*.

:::tip[An `.ec` opens as plain text, without validation]
Older versions of the plugin added `.ec` to the YAML file type. If the IDE once recorded `*.ec` as
removed from YAML — it keeps that in `filetypes.xml` as
`<removed_mapping ext="ec" type="YAML" approved="true"/>` — it stripped the association again on
every start, whatever the plugin version, and `.ec` lost highlighting and validation. Current
versions register their own **EventConductor** file type, which that entry does not affect.
On an older version, add `*.ec` (and `*.ecform`) back under *Settings → Editor → File Types →
YAML*.
:::
- **Theme** — the graph follows the IDE's light/dark theme.

Install from the JetBrains Marketplace (search *EventConductor*), or from the `.zip` in the
[GitHub releases](https://github.com/miguelperezcolom/eventconductor/releases): Settings → Plugins
→ ⚙ → **Install Plugin from Disk…**.

:::note
In both IDEs, graphical edits are written back as the smallest change to the file: only the lines of what
changed are touched, so YAML comments, quoting, indentation and blank lines everywhere else stay
exactly as they were (a JSON file is rewritten as formatted JSON — it has no comments to keep).
Node positions live in the definition's own `layout` block, so everybody who opens the file sees
the same picture. It is written only when you drag a node — clicking one to select it, or dropping
a new step on an unarranged graph, writes no coordinates. The first drag records every step's
position (half an arrangement would let the unrecorded steps land on top of the recorded ones);
after that, moving a node changes only that node's `x`/`y` lines. **Re-layout** removes the block.
:::

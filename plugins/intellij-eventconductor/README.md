# EventConductor for IntelliJ IDEA

Edit EventConductor workflow definitions (`*.ec.yaml` files — recommended — or `.ec`) as an **interactive graph** or as
plain **YAML/JSON** with schema validation — in one split editor, both views backed by the
same document.

## Features

- **Split editor** for `*.ec.yaml` (the recommended name) — and for `*.ec` / `*.workflow.yaml`
  (and `.yml`): the text (YAML/JSON) on one side and the EventConductor graph
  (the same web component the app uses, embedded via **JCEF**) on the other. Use the editor's
  **Editor / Split / Preview** toolbar toggles to focus on text, graph, or both.
- **Two-way sync**: graphical edits update the document and text edits update the graph.
- **Delete** (or **Backspace**) removes the selected node or connection, clearing every
  reference other steps held to a deleted node — preconditions and compensation pointers.
  The graph takes keyboard focus when you click in it; if the IDE consumes `Delete` before
  the embedded browser sees it, `Backspace` does the same thing.
- **`.ec` is JSON *or* YAML**: the format is detected from the file and preserved on graphical
  edits (conversion happens in the embedded page; the Kotlin host only moves text).
- **Lossless edits**: graphical edits are written back as the smallest change to the file: only the lines of what
  changed are touched, so YAML comments, quoting, indentation and blank lines everywhere else stay
  exactly as they were (a JSON file is rewritten as formatted JSON — it has no comments to keep).
  Node positions live in the definition's own `layout` block, so everybody who opens the file sees
  the same picture. It is written only when you drag a node — clicking one to select it, or dropping
  a new step on an unarranged graph, writes no coordinates. The first drag records every step's
  position (half an arrangement would let the unrecorded steps land on top of the recorded ones);
  after that, moving a node changes only that node's `x`/`y` lines. **Re-layout** removes the block.
  The write-back is the VS Code extension's `src/yamlEdit.ts`, bundled for the webviews as
  `src/main/resources/webview/ec-yaml-edit.js` (`npm run bundle-intellij` in
  `plugins/vscode-eventconductor`); the document receives only the span that changed.
- **Schema validation** for `.ec` (and the compound suffixes above) against the
  workflow-definition JSON schema (works for JSON and, with the bundled YAML support, YAML too).
  `.ec`, `.ecform`, `.ecrule` and `.ectask` are an **EventConductor** file type of their own whose
  language is YAML — not extra extensions on the YAML file type, which a stale
  `<removed_mapping ext="ec" type="YAML"/>` in the IDE's `filetypes.xml` could strip on every start.

## Build & run

Requires **JDK 17+** and **Gradle** (or generate the wrapper once with `gradle wrapper`).

```bash
cd plugins/intellij-eventconductor
gradle wrapper           # first time only, creates ./gradlew
./gradlew runIde         # launches a sandbox IDE with the plugin
./gradlew buildPlugin    # produces build/distributions/*.zip to install
./gradlew test           # headless platform tests (file types, schema association, validation)
```

`processResources` runs the `syncBundle` / `syncSchema` tasks, which copy the current graph
bundle (`workflow-graph.js`) and the JSON schema from the main repo into the plugin's
resources (git-ignored). Re-run a build after rebuilding the frontend to pick up changes.

> JCEF must be available in the running IDE (it ships with all recent IntelliJ builds).

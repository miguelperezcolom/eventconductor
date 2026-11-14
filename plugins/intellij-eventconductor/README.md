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
  edits (conversion happens in the embedded page via js-yaml; the Kotlin host only moves text).
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

> Note: graphical edits round-trip the definition through the model, so YAML comments and exact
> formatting aren't preserved when editing in the graph — use the text side to keep them.
> JCEF must be available in the running IDE (it ships with all recent IntelliJ builds).

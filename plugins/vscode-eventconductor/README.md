# EventConductor for VS Code

Edit EventConductor workflow definitions (`.ec` files) as an **interactive graph** or as
plain **YAML/JSON** with schema validation — both views edit the same file.

## Features

- **Graph editor** (default): opens any `.ec` file as the EventConductor workflow graph.
  Drag nodes, **Shift+drag** to draw precondition lines, click to filter, zoom/pan/fit,
  minimap, and the animated token simulation — the same component used inside the app.
- **Delete** (or **Backspace**) removes the selected node or connection. Deleting a node
  also clears every reference to it — preconditions and compensation pointers — so the
  file it writes back is still a definition the engine will load.
- **Dual mode**: the graph is backed by the text document, so graphical edits update the
  file and text edits update the graph. Use **EventConductor: Open as Text** (editor title
  or command palette) to edit the YAML/JSON directly, and **Open as Graph** to go back.
- **`.ec` is JSON *or* YAML**: the format is detected from the file's content and preserved
  on graphical edits.
- **Schema validation**: `.ec` files are validated against the workflow-definition JSON
  schema (JSON bodies out of the box; for YAML install *YAML* by Red Hat and point
  `yaml.schemas` at `./schema/ec.schema.json`).

## Develop

```bash
cd plugins/vscode-eventconductor
npm install
npm run compile     # syncs the graph bundle + schema, then compiles TypeScript
```

Then press **F5** in VS Code (Extension Development Host) and open a `.ec` file.

`npm run compile` runs `scripts/sync-assets.js`, which copies the graph bundle
(`workflow-graph.js`) and the JSON schema from the main repo into the extension, so the
extension always ships the current component. Re-run it after rebuilding the frontend.

> Note: graphical edits round-trip the definition through the model, so YAML comments and
> exact formatting are not preserved when you edit in the graph — use the text editor when
> you need to keep them.

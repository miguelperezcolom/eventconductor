import * as vscode from "vscode";
import * as yaml from "js-yaml";

const GRAPH_VIEW_TYPE = "eventconductor.graphEditor";
const FORM_VIEW_TYPE = "eventconductor.formEditor";

export function activate(context: vscode.ExtensionContext) {
  context.subscriptions.push(
    // The .ec graph editor and the .ecform visual editor share the same webview machinery, differing
    // only in the component they host and the default new-document body — see EditorConfig.
    vscode.window.registerCustomEditorProvider(GRAPH_VIEW_TYPE, new EventConductorEditorProvider(context, GRAPH_CONFIG), {
      webviewOptions: { retainContextWhenHidden: true },
      supportsMultipleEditorsPerDocument: false,
    }),
    vscode.window.registerCustomEditorProvider(FORM_VIEW_TYPE, new EventConductorEditorProvider(context, FORM_CONFIG), {
      webviewOptions: { retainContextWhenHidden: true },
      supportsMultipleEditorsPerDocument: false,
    }),
    // .ec graph editor commands.
    vscode.commands.registerCommand("eventconductor.openAsText", () => reopenActive("default")),
    vscode.commands.registerCommand("eventconductor.openAsGraph", () => reopenActive(GRAPH_VIEW_TYPE)),
    vscode.commands.registerCommand("eventconductor.showTextBeside", () => openActiveBeside()),
    // .ecform visual editor commands (mirrors the .ec ones).
    vscode.commands.registerCommand("eventconductor.form.openAsText", () => reopenActive("default")),
    vscode.commands.registerCommand("eventconductor.form.openAsForm", () => reopenActive(FORM_VIEW_TYPE)),
    vscode.commands.registerCommand("eventconductor.form.showTextBeside", () => openActiveBeside())
  );
  // Validate .ec / .ecform (YAML or JSON) against their bundled schemas.
  registerYamlSchema(context);
}

export function deactivate() {}

/**
 * Bind the bundled JSON schemas through the Red Hat YAML extension's API, so YAML (and JSON, a
 * YAML subset) definition files get validation and completion in the text view: `*.ec` against the
 * workflow-definition schema, `*.ecform` against the form schema.
 */
async function registerYamlSchema(context: vscode.ExtensionContext) {
  try {
    const yamlExt = vscode.extensions.getExtension("redhat.vscode-yaml");
    if (!yamlExt) return;
    const api = await yamlExt.activate();
    if (!api || typeof api.registerContributor !== "function") return;
    const ecSchemaUri = vscode.Uri.joinPath(context.extensionUri, "schema", "ec.schema.json").toString();
    const formSchemaUri = vscode.Uri.joinPath(context.extensionUri, "schema", "form-schema.json").toString();
    api.registerContributor(
      "eventconductor",
      (resource: string) => {
        if (resource.endsWith(".ec")) return ecSchemaUri;
        if (resource.endsWith(".ecform")) return formSchemaUri;
        return undefined;
      },
      () => undefined
    );
  } catch {
    /* YAML extension missing or API changed — schema validation just won't be active */
  }
}

/** The file shown in the active tab (custom-editor tabs expose their uri on the input). */
function activeUri(): vscode.Uri | undefined {
  const input = vscode.window.tabGroups.activeTabGroup.activeTab?.input as
    | { uri?: vscode.Uri }
    | undefined;
  return input?.uri;
}

/** Reopen the file in the active tab with the given editor (text = "default", visual = view type). */
function reopenActive(viewType: string) {
  const uri = activeUri();
  if (uri) vscode.commands.executeCommand("vscode.openWith", uri, viewType);
}

/** Open the raw YAML/JSON in a text editor beside the visual editor, so both show the same file. */
function openActiveBeside() {
  const uri = activeUri();
  if (uri) vscode.commands.executeCommand("vscode.openWith", uri, "default", vscode.ViewColumn.Beside);
}

/**
 * What differs between the .ec graph editor and the .ecform visual editor: the hosted web component,
 * the bundle + bridge scripts, the new-document body and the labels shown to the user. Everything
 * else (the YAML<->JSON round-trip, the document sync, the CSP) is identical and lives in the
 * provider below.
 */
interface EditorConfig {
  /** The custom-element tag hosted in the webview. */
  tag: string;
  /** The component bundle in media/. */
  bundle: string;
  /** The webview bridge script in media/. */
  main: string;
  /** File extension, for error messages. */
  ext: string;
  /** Body for a new/empty document, so drawing in an empty file yields a sensible default. */
  emptyValue: () => unknown;
}

const GRAPH_CONFIG: EditorConfig = {
  tag: "eventconductor-workflow-graph",
  bundle: "workflow-graph.js",
  main: "main.js",
  ext: ".ec",
  emptyValue: () => ({ name: "New Workflow", steps: [] }),
};

const FORM_CONFIG: EditorConfig = {
  tag: "eventconductor-form-editor",
  bundle: "form-editor.js",
  main: "form-main.js",
  ext: ".ecform",
  emptyValue: () => ({ name: "New Form", fields: [] }),
};

class EventConductorEditorProvider implements vscode.CustomTextEditorProvider {
  constructor(
    private readonly context: vscode.ExtensionContext,
    private readonly config: EditorConfig
  ) {}

  resolveCustomTextEditor(
    document: vscode.TextDocument,
    webviewPanel: vscode.WebviewPanel
  ): void {
    const webview = webviewPanel.webview;
    webview.options = {
      enableScripts: true,
      localResourceRoots: [vscode.Uri.joinPath(this.context.extensionUri, "media")],
    };
    webview.html = this.getHtml(webview);

    // The component speaks JSON; the file may be YAML — remember which so edits round-trip back
    // into the author's chosen format.
    let docIsYaml = looksLikeYaml(document.getText());
    let applyingOwnEdit = false;

    const pushToWebview = () => {
      const text = document.getText();
      docIsYaml = looksLikeYaml(text);
      try {
        const obj = text.trim() ? parseDefinition(text) : this.config.emptyValue();
        webview.postMessage({ type: "setValue", value: JSON.stringify(obj, null, 2) });
      } catch (e) {
        webview.postMessage({ type: "parseError", message: String(e) });
      }
    };

    const changeSub = vscode.workspace.onDidChangeTextDocument((e) => {
      if (e.document.uri.toString() !== document.uri.toString()) return;
      if (applyingOwnEdit) return; // our own write — don't echo it back
      pushToWebview();
    });
    webviewPanel.onDidDispose(() => changeSub.dispose());

    webview.onDidReceiveMessage(async (msg) => {
      if (msg?.type === "ready") {
        pushToWebview();
      } else if (msg?.type === "edit") {
        let obj: unknown;
        try {
          obj = JSON.parse(msg.value);
        } catch {
          return; // ignore malformed payloads
        }
        const newText = serialize(obj, docIsYaml);
        if (newText === document.getText()) return;
        applyingOwnEdit = true;
        const edit = new vscode.WorkspaceEdit();
        edit.replace(
          document.uri,
          new vscode.Range(0, 0, document.lineCount, 0),
          newText
        );
        await vscode.workspace.applyEdit(edit);
        applyingOwnEdit = false;
      }
    });
  }

  private getHtml(webview: vscode.Webview): string {
    const media = (name: string) =>
      webview.asWebviewUri(vscode.Uri.joinPath(this.context.extensionUri, "media", name));
    const bundle = media(this.config.bundle);
    const main = media(this.config.main);
    const tag = this.config.tag;
    const nonce = getNonce();
    const csp =
      `default-src 'none'; img-src ${webview.cspSource} data:; ` +
      `style-src ${webview.cspSource} 'unsafe-inline'; ` +
      `font-src ${webview.cspSource}; ` +
      `script-src 'nonce-${nonce}';`;
    return `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8" />
  <meta http-equiv="Content-Security-Policy" content="${csp}" />
  <meta name="viewport" content="width=device-width, initial-scale=1.0" />
  <style>
    html, body { height: 100%; margin: 0; padding: 0; }
    body { display: flex; }
    ${tag} { flex: 1 1 auto; min-height: 0; }
    #error { display: none; position: absolute; top: 8px; left: 8px; right: 8px;
             padding: 6px 10px; border-radius: 6px; font: 12px/1.4 var(--vscode-font-family);
             background: var(--vscode-inputValidation-errorBackground, #5a1d1d);
             color: var(--vscode-inputValidation-errorForeground, #fff);
             border: 1px solid var(--vscode-inputValidation-errorBorder, #be1100); z-index: 10; }
  </style>
</head>
<body>
  <div id="error"></div>
  <!-- no-expand: the custom editor already fills the pane, so expanding it means nothing -->
  <${tag} no-expand></${tag}>
  <script nonce="${nonce}" type="module" src="${bundle}"></script>
  <script nonce="${nonce}" src="${main}"></script>
</body>
</html>`;
  }
}

/**
 * A .ec / .ecform body is YAML unless it clearly starts as JSON (a leading '{' or '[').
 *
 * An empty file is YAML too: there is nothing to sniff, and a new file that the visual editor fills
 * in should come out looking like the ones in the documentation and the examples, all of which are
 * YAML. It used to answer JSON here, so creating a file and drawing in it produced JSON — a format
 * nobody chose, from a file that had not said anything.
 */
function looksLikeYaml(text: string): boolean {
  const t = text.trimStart();
  if (!t) return true;
  return !(t.startsWith("{") || t.startsWith("["));
}

function parseDefinition(text: string): unknown {
  return looksLikeYaml(text) ? yaml.load(text) : JSON.parse(text);
}

function serialize(obj: unknown, asYaml: boolean): string {
  return asYaml ? yaml.dump(obj, { noRefs: true, lineWidth: 120 }) : JSON.stringify(obj, null, 2) + "\n";
}

function getNonce(): string {
  let text = "";
  const chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
  for (let i = 0; i < 32; i++) text += chars.charAt(Math.floor(Math.random() * chars.length));
  return text;
}

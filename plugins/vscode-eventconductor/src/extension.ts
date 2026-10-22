import * as vscode from "vscode";
import * as yaml from "js-yaml";
import { registerWorkflowExecution, GraphExecutionController, executionMarkup } from "./execution";
import { catalogueEndpoint, fetchAgentCatalogue } from "./agentCatalogue";
import {
  applicationYaml,
  artifactIdFor,
  GITIGNORE,
  pomXml,
  readme,
  TaskContract,
  Variant,
} from "./taskProject";

const GRAPH_VIEW_TYPE = "eventconductor.graphEditor";
const FORM_VIEW_TYPE = "eventconductor.formEditor";
const CATALOGUE_SECRET_PREFIX = "eventconductor.agentCatalogue.auth:";

export function activate(context: vscode.ExtensionContext) {
  registerWorkflowExecution(context, activeUri, async (uri, id) => {
    let target = uri;
    if (!target || !isWorkflowFile(target.path)) {
      if (!id) throw new Error("Open a workflow definition first.");
      target = (await vscode.workspace.openTextDocument({ language: "yaml", content: "name: Execution inspection\nsteps: []\n" })).uri;
    }
    await vscode.commands.executeCommand("vscode.openWith", target, GRAPH_VIEW_TYPE);
    const view = graphExecutions.get(target.toString());
    if (!view) throw new Error("The workflow graph editor could not be opened.");
    view.panel.reveal();
    await view.execution.open(id);
  });
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
    vscode.commands.registerCommand("eventconductor.exportSvg", () => exportActiveGraph()),
    vscode.commands.registerCommand("eventconductor.configureAgentCatalogue", () => configureAgentCatalogue(context)),
    vscode.workspace.onDidChangeConfiguration(e => {
      if (e.affectsConfiguration("eventconductor.agentCatalogueUrl")) reloadAgentCatalogues();
    }),
    context.secrets.onDidChange(e => {
      if (e.key.startsWith(CATALOGUE_SECRET_PREFIX)) reloadAgentCatalogues();
    }),
    vscode.workspace.onDidGrantWorkspaceTrust(() => reloadAgentCatalogues()),
    // .ecform visual editor commands (mirrors the .ec ones).
    vscode.commands.registerCommand("eventconductor.form.openAsText", () => reopenActive("default")),
    vscode.commands.registerCommand("eventconductor.form.openAsForm", () => reopenActive(FORM_VIEW_TYPE)),
    vscode.commands.registerCommand("eventconductor.form.showTextBeside", () => openActiveBeside()),
    // Task-contract scaffolding: from a .ectask, generate a worker project or wire a module to it.
    vscode.commands.registerCommand("eventconductor.createTaskModule", (uri?: vscode.Uri) => createTaskProject("module", uri)),
    vscode.commands.registerCommand("eventconductor.createTaskService", (uri?: vscode.Uri) => createTaskProject("service", uri)),
    vscode.commands.registerCommand("eventconductor.addTaskDependency", (uri?: vscode.Uri) => addTaskDependency(uri))
  );
  // Validate .ec / .ecform (YAML or JSON) against their bundled schemas.
  registerYamlSchema(context);
}

export function deactivate() {}

function reloadAgentCatalogues() {
  for (const panel of graphPanels) panel.webview.postMessage({ type: "reloadAgentCatalogue" });
}

async function configureAgentCatalogue(context: vscode.ExtensionContext) {
  if (!vscode.workspace.isTrusted) {
    vscode.window.showErrorMessage("Trust this workspace before connecting to an agent catalogue.");
    return;
  }
  const configuration = vscode.workspace.getConfiguration("eventconductor");
  const url = await vscode.window.showInputBox({
    title: "Agent catalogue endpoint",
    prompt: "Full URL of the workflow engine's /api/workflow-agents endpoint",
    value: configuration.get<string>("agentCatalogueUrl") || "http://127.0.0.1:18105/api/workflow-agents",
    ignoreFocusOut: true,
    validateInput: value => {
      try { catalogueEndpoint(value); return undefined; }
      catch (error) { return (error as Error).message; }
    },
  });
  if (url === undefined) return;
  const endpoint = catalogueEndpoint(url);
  const choice = await vscode.window.showQuickPick([
    { label: "HTTP Basic", auth: "basic", description: "Username and password (local engine)" },
    { label: "Bearer token", auth: "bearer", description: "An existing access token" },
    { label: "No authentication", auth: "none", description: "An endpoint that allows anonymous access" },
  ], { title: "Agent catalogue authentication", ignoreFocusOut: true });
  if (!choice) return;
  if (choice.auth !== "none" && endpoint.protocol !== "https:" && !["localhost", "127.0.0.1", "[::1]"].includes(endpoint.hostname)) {
    vscode.window.showErrorMessage("Catalogue authentication requires HTTPS, except on localhost.");
    return;
  }
  let authorization: string | undefined;
  if (choice.auth === "basic") {
    const username = await vscode.window.showInputBox({
      title: "Catalogue username", value: "local-operator", ignoreFocusOut: true,
      validateInput: value => !value || /[:\r\n]/.test(value) ? "Enter a username without colons or line breaks." : undefined,
    });
    if (username === undefined) return;
    const password = await vscode.window.showInputBox({ title: "Catalogue password", password: true, ignoreFocusOut: true });
    if (password === undefined) return;
    authorization = "Basic " + Buffer.from(`${username}:${password}`, "utf8").toString("base64");
  } else if (choice.auth === "bearer") {
    const token = await vscode.window.showInputBox({
      title: "Catalogue access token", password: true, ignoreFocusOut: true,
      validateInput: value => !value.trim() || /\s/.test(value) ? "Enter a token without whitespace." : undefined,
    });
    if (token === undefined) return;
    authorization = "Bearer " + token;
  }
  const key = CATALOGUE_SECRET_PREFIX + endpoint.href;
  if (authorization) await context.secrets.store(key, authorization);
  else await context.secrets.delete(key);
  await configuration.update("agentCatalogueUrl", endpoint.href, vscode.ConfigurationTarget.Global);
  reloadAgentCatalogues();
  vscode.window.showInformationMessage("Agent catalogue connection saved. Open graphs will reload it.");
}

/** The .ectask to act on: the explorer/editor menu passes it; otherwise use the active tab. */
function taskUri(uri?: vscode.Uri): vscode.Uri | undefined {
  if (uri) return uri;
  const active = activeUri();
  return active?.path.endsWith(".ectask") ? active : undefined;
}

async function readContract(uri: vscode.Uri): Promise<TaskContract> {
  const bytes = await vscode.workspace.fs.readFile(uri);
  return yaml.load(Buffer.from(bytes).toString("utf8")) as TaskContract;
}

/** Generate a task-module or task-service project next to the workspace root and open its pom. */
async function createTaskProject(variant: Variant, uri?: vscode.Uri) {
  const contractUri = taskUri(uri);
  if (!contractUri) {
    vscode.window.showErrorMessage("Run this on a .ectask task contract.");
    return;
  }
  const workspace = vscode.workspace.getWorkspaceFolder(contractUri) ?? vscode.workspace.workspaceFolders?.[0];
  if (!workspace) {
    vscode.window.showErrorMessage("No workspace folder is open.");
    return;
  }
  const contract = await readContract(contractUri);
  const group = contract.group ?? "tasks";

  const artifactId = await vscode.window.showInputBox({
    prompt: "Artifact id",
    value: artifactIdFor(group, variant),
  });
  if (!artifactId) return;
  const basePackage = await vscode.window.showInputBox({ prompt: "Base package", value: "com.example" });
  if (!basePackage) return;

  const moduleDir = vscode.Uri.joinPath(workspace.uri, artifactId);
  const write = async (relative: string, content: string) => {
    const target = vscode.Uri.joinPath(moduleDir, ...relative.split("/"));
    await vscode.workspace.fs.writeFile(target, Buffer.from(content, "utf8"));
  };

  await write("pom.xml", pomXml(variant, "com.example", artifactId, group, basePackage));
  await write("README.md", readme(variant, artifactId, contract));
  // The contract travels with the project so it builds on its own (the goal reads it locally).
  await write(`src/main/resources/tasks/${contractUri.path.split("/").pop()}`,
    Buffer.from(await vscode.workspace.fs.readFile(contractUri)).toString("utf8"));
  if (variant === "service") {
    await write("src/main/resources/application.yaml", applicationYaml(artifactId));
    await write(".gitignore", GITIGNORE);
    // The Maven wrapper (mvnw / .mvn) is added by `mvn wrapper:wrapper`; noted in the README.
  }

  const pom = vscode.Uri.joinPath(moduleDir, "pom.xml");
  vscode.window.showTextDocument(await vscode.workspace.openTextDocument(pom));
  vscode.window.showInformationMessage(`Created ${artifactId}.`);
}

/** Insert a dependency on the task's `<group>-tasks` module into the nearest pom.xml above the file. */
async function addTaskDependency(uri?: vscode.Uri) {
  const contractUri = taskUri(uri);
  if (!contractUri) {
    vscode.window.showErrorMessage("Run this on a .ectask task contract.");
    return;
  }
  const contract = await readContract(contractUri);
  const artifact = artifactIdFor(contract.group ?? "tasks", "module");

  const pom = await findNearestPom(contractUri);
  if (!pom) {
    vscode.window.showErrorMessage("No pom.xml found above this file.");
    return;
  }
  let text = Buffer.from(await vscode.workspace.fs.readFile(pom)).toString("utf8");
  if (text.includes(`<artifactId>${artifact}</artifactId>`)) {
    vscode.window.showInformationMessage(`${artifact} is already a dependency.`);
    return;
  }
  const dependency =
    `        <dependency>\n` +
    `            <groupId>com.example</groupId>\n` +
    `            <artifactId>${artifact}</artifactId>\n` +
    `            <version>0.1.0-SNAPSHOT</version>\n` +
    `        </dependency>`;
  text = text.includes("</dependencies>")
    ? text.replace("</dependencies>", `${dependency}\n    </dependencies>`)
    : text.replace("</project>", `    <dependencies>\n${dependency}\n    </dependencies>\n</project>`);
  await vscode.workspace.fs.writeFile(pom, Buffer.from(text, "utf8"));
  vscode.window.showInformationMessage(`Added ${artifact} to ${vscode.workspace.asRelativePath(pom)}.`);
}

/** Walk up from a file to the first directory that has a pom.xml. */
async function findNearestPom(from: vscode.Uri): Promise<vscode.Uri | undefined> {
  let dir = vscode.Uri.joinPath(from, "..");
  const workspace = vscode.workspace.getWorkspaceFolder(from);
  const stop = workspace ? workspace.uri.path : "/";
  // Bounded walk up to the workspace root.
  for (let i = 0; i < 64; i++) {
    const pom = vscode.Uri.joinPath(dir, "pom.xml");
    try {
      await vscode.workspace.fs.stat(pom);
      return pom;
    } catch {
      /* no pom here, keep walking */
    }
    if (dir.path === stop || dir.path === "/") break;
    dir = vscode.Uri.joinPath(dir, "..");
  }
  return undefined;
}

/**
 * The file names that hold a workflow definition: the `.ec` extension, and the compound suffixes
 * that say "workflow" while staying `.yaml` files every tool already knows. Must match the
 * `customEditors` selector and the `yamlValidation` entry in package.json.
 */
const WORKFLOW_SUFFIXES = [".ec", ".workflow.yaml", ".workflow.yml", ".ec.yaml", ".ec.yml"];

function isWorkflowFile(path: string): boolean {
  const p = path.toLowerCase();
  return WORKFLOW_SUFFIXES.some((suffix) => p.endsWith(suffix));
}

/**
 * Bind the bundled JSON schemas through the Red Hat YAML extension's API, so YAML (and JSON, a
 * YAML subset) definition files get validation and completion in the text view: workflow files
 * (`*.ec`, `*.workflow.yaml`, `*.ec.yaml`, ...) against the workflow-definition schema, `*.ecform`
 * against the form schema, `*.ecrule` and `*.ectask` against theirs.
 *
 * The same associations are declared in package.json under `yamlValidation`, which is what the YAML
 * extension reads without this extension being active — it only activates with a custom editor, so
 * opening a `.ecrule` or `.ectask` never runs this. (`jsonValidation` does not help there: it only
 * applies to documents in the JSON language, and these are YAML.)
 */
async function registerYamlSchema(context: vscode.ExtensionContext) {
  try {
    const yamlExt = vscode.extensions.getExtension("redhat.vscode-yaml");
    if (!yamlExt) return;
    const api = await yamlExt.activate();
    if (!api || typeof api.registerContributor !== "function") return;
    const ecSchemaUri = vscode.Uri.joinPath(context.extensionUri, "schema", "ec.schema.json").toString();
    const formSchemaUri = vscode.Uri.joinPath(context.extensionUri, "schema", "form-schema.json").toString();
    const ruleSchemaUri = vscode.Uri.joinPath(context.extensionUri, "schema", "rule.schema.json").toString();
    const taskSchemaUri = vscode.Uri.joinPath(context.extensionUri, "schema", "task.schema.json").toString();
    api.registerContributor(
      "eventconductor",
      (resource: string) => {
        if (isWorkflowFile(resource)) return ecSchemaUri;
        const r = resource.toLowerCase();
        if (r.endsWith(".ecform")) return formSchemaUri;
        if (r.endsWith(".ecrule")) return ruleSchemaUri;
        if (r.endsWith(".ectask")) return taskSchemaUri;
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

/** Open graph editor panels, so a command can reach the webview of the one in front. */
const graphPanels = new Set<vscode.WebviewPanel>();
const graphExecutions = new Map<string, { panel: vscode.WebviewPanel; execution: GraphExecutionController }>();

/**
 * "Export Graph as SVG" from the title bar or the palette. Only the webview can draw the SVG, so
 * this asks the active graph editor for it; the answer comes back as the same `exportSvg`
 * message the in-graph export button sends, and is saved by the provider below.
 */
function exportActiveGraph() {
  const panel = [...graphPanels].find((p) => p.active);
  if (!panel) {
    vscode.window.showWarningMessage("Open a .ec file in the EventConductor graph editor to export it.");
    return;
  }
  panel.webview.postMessage({ type: "requestExport" });
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
    let disposed = false;
    let catalogueRequestId = 0;
    let pendingDocumentEdit: Promise<void> = Promise.resolve();

    const execution = this.config.tag === GRAPH_CONFIG.tag
      ? new GraphExecutionController(this.context, webviewPanel, document, () => pendingDocumentEdit) : undefined;
    if (execution) graphExecutions.set(document.uri.toString(), { panel: webviewPanel, execution });

    const pushAgentCatalogue = async () => {
      const requestId = ++catalogueRequestId;
      let entries: Awaited<ReturnType<typeof fetchAgentCatalogue>> = [];
      let error = "";
      try {
        if (!vscode.workspace.isTrusted) throw new Error("Trust this workspace before connecting to an agent catalogue.");
        const url = vscode.workspace.getConfiguration("eventconductor").get<string>("agentCatalogueUrl");
        if (!url) throw new Error("Agent catalogue not configured. Run EventConductor: Configure Agent Catalogue.");
        const endpoint = catalogueEndpoint(url);
        const authorization = await this.context.secrets.get(CATALOGUE_SECRET_PREFIX + endpoint.href);
        entries = await fetchAgentCatalogue(endpoint.href, authorization);
      } catch (cause) { error = cause instanceof Error ? cause.message : "Agent catalogue unavailable."; }
      if (!disposed && requestId === catalogueRequestId) {
        webview.postMessage({ type: "agentCatalogue", entries, error });
      }
    };

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
    if (this.config.tag === GRAPH_CONFIG.tag) graphPanels.add(webviewPanel);
    webviewPanel.onDidDispose(() => {
      disposed = true;
      changeSub.dispose();
      graphPanels.delete(webviewPanel);
      if (graphExecutions.get(document.uri.toString())?.panel === webviewPanel) graphExecutions.delete(document.uri.toString());
    });

    webview.onDidReceiveMessage(async (msg) => {
      if (msg?.type === "ready") {
        pushToWebview();
        await execution?.message(msg);
      } else if (msg?.type === "runWorkflow" && this.config.tag === GRAPH_CONFIG.tag && !disposed) {
        if (!vscode.workspace.isTrusted) {
          void vscode.window.showErrorMessage("Trust this workspace before executing workflows on an engine.");
          return;
        }
        try {
          await pendingDocumentEdit;
          if (!disposed) await execution?.open();
        } catch {
          void vscode.window.showErrorMessage("The graph changes could not be applied. Fix the document before executing it.");
        }
      } else if (msg?.type === "requestAgentCatalogue" && this.config.tag === GRAPH_CONFIG.tag) {
        await pushAgentCatalogue();
      } else if (msg?.type === "edit") {
        const apply = pendingDocumentEdit.catch(() => {}).then(async () => {
          if (disposed) return;
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
          try {
            if (!await vscode.workspace.applyEdit(edit)) throw new Error("VS Code refused the graph changes.");
          } finally { applyingOwnEdit = false; }
        });
        pendingDocumentEdit = apply;
        try { await apply; }
        catch (error) { void vscode.window.showErrorMessage(error instanceof Error ? error.message : "Could not apply graph changes."); }
      } else if (msg?.type === "exportSvg") {
        // The webview cannot save a file itself, so the export arrives here as text. Offered
        // beside the definition it came from and named after it (orders.ec -> orders.svg), which
        // is where somebody exporting a diagram of this file is going to look for it.
        const base = document.uri.path.split("/").pop()!.replace(/\.[^.]*$/, "") || "workflow";
        const target = await vscode.window.showSaveDialog({
          defaultUri: vscode.Uri.joinPath(vscode.Uri.joinPath(document.uri, ".."), `${base}.svg`),
          filters: { "SVG image": ["svg"] },
          title: "Export workflow graph",
        });
        if (!target) return; // cancelled
        await vscode.workspace.fs.writeFile(
          target,
          Buffer.from(String(msg.svg ?? ""), "utf8")
        );
        vscode.window.showInformationMessage(
          `Graph exported to ${vscode.workspace.asRelativePath(target)}`
        );
      } else {
        await execution?.message(msg);
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
    const graph = this.config.tag === GRAPH_CONFIG.tag;
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
  ${graph ? `<link rel="stylesheet" href="${media("execution.css")}">` : ""}
  <style>
    html, body { height: 100%; margin: 0; padding: 0; }
    body { display: flex; flex-direction: column; overflow: hidden; }
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
  <${tag} no-expand${this.config.tag === GRAPH_CONFIG.tag ? " host-agent-catalogue host-execution" : ""}></${tag}>
  <script nonce="${nonce}" type="module" src="${bundle}"></script>
  ${graph ? executionMarkup() + `<script nonce="${nonce}" src="${media("graph-state.js")}"></script><script nonce="${nonce}" src="${media("execution.js")}"></script>` : ""}
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

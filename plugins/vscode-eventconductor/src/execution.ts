import * as vscode from "vscode";
import { parseRunWorkflow, workflowInputTemplate } from "./inputTemplate";
import { randomUUID } from "node:crypto";
import { EngineClient, EngineCredentials, EngineError, engineEndpoint, processId, TestRunRequest } from "./engineClient";
import { ExecutionSession } from "./executionSession";
import { ExecutionCancellation } from "./executionCancellation";

const SECRET = "eventconductor.testEngine.auth:";
const HISTORY = "eventconductor.executions";
interface RecentRun { id: string; endpoint: string; name: string; }

export function registerWorkflowExecution(context: vscode.ExtensionContext, activeUri: () => vscode.Uri | undefined,
  openGraph: (uri?: vscode.Uri, executionId?: string) => Promise<void>): void {
  context.subscriptions.push(
    vscode.commands.registerCommand("eventconductor.configureTestEngine", () => configureTestEngine(context)),
    vscode.commands.registerCommand("eventconductor.runWorkflow", async (uri?: vscode.Uri) => {
      try {
        requireTrust();
        const target = uri ?? activeUri();
        if (!target || !/\.(ec|workflow\.ya?ml|ec\.ya?ml)$/i.test(target.path)) throw new Error("Open a workflow definition first.");
        await openGraph(target);
      } catch (error) { showError(error); }
    }),
    vscode.commands.registerCommand("eventconductor.openExecution", async () => {
      try {
        requireTrust();
        const client = await configuredClient(context);
        const entries = context.workspaceState.get<RecentRun[]>(HISTORY, []).filter(run => run.endpoint === client.endpoint.href);
        const picked = await vscode.window.showQuickPick([
          { label: "Enter an execution ID…", id: "", description: "Open another execution on this engine" },
          ...entries.map(run => ({ label: run.name, id: run.id, description: run.id })),
        ], { title: "EventConductor execution" });
        if (!picked) return;
        const id = picked.id || await vscode.window.showInputBox({ title: "Execution ID", ignoreFocusOut: true,
          validateInput: value => { try { processId(value); return undefined; } catch { return "Enter a valid execution ID."; } } });
        if (id) await openGraph(activeUri(), processId(id));
      } catch (error) { showError(error); }
    }),
  );
}

function requireTrust(): void {
  if (!vscode.workspace.isTrusted) throw new Error("Trust this workspace before connecting to or executing workflows on an engine.");
}
function showError(error: unknown): void { void vscode.window.showErrorMessage(error instanceof Error ? error.message : "Execution request failed."); }

async function configuredClient(context: vscode.ExtensionContext): Promise<EngineClient> {
  const value = vscode.workspace.getConfiguration("eventconductor").get<string>("testEngineUrl");
  if (!value) throw new Error("Run EventConductor: Configure Test Engine first.");
  const endpoint = engineEndpoint(value);
  const stored = await context.secrets.get(SECRET + endpoint.href);
  const credentials: EngineCredentials = stored ? JSON.parse(stored) : {};
  return new EngineClient(endpoint.href, credentials);
}

async function configureTestEngine(context: vscode.ExtensionContext): Promise<void> {
  try {
    requireTrust();
    const config = vscode.workspace.getConfiguration("eventconductor");
    const value = await vscode.window.showInputBox({ title: "Test engine URL", prompt: "Engine base URL, including its context path if any",
      value: config.get<string>("testEngineUrl") || "http://127.0.0.1:8080", ignoreFocusOut: true,
      validateInput: input => { try { engineEndpoint(input); return undefined; } catch (error) { return (error as Error).message; } } });
    if (value === undefined) return;
    const endpoint = engineEndpoint(value);
    const method = await vscode.window.showQuickPick([
      { label: "API key", auth: "key", description: "X-Api-Key" },
      { label: "Bearer token", auth: "bearer", description: "Existing access token" },
      { label: "HTTP Basic", auth: "basic", description: "Username and password" },
      { label: "No authentication", auth: "none", description: "An explicitly anonymous test engine" },
    ], { title: "Test engine authentication", ignoreFocusOut: true });
    if (!method) return;
    const credentials: EngineCredentials = {};
    if (method.auth === "basic") {
      const username = await vscode.window.showInputBox({ title: "Engine username", ignoreFocusOut: true,
        validateInput: input => !input || /[:\r\n]/.test(input) ? "Enter a username without colons or line breaks." : undefined });
      if (username === undefined) return;
      const password = await vscode.window.showInputBox({ title: "Engine password", password: true, ignoreFocusOut: true });
      if (password === undefined) return;
      credentials.authorization = "Basic " + Buffer.from(`${username}:${password}`, "utf8").toString("base64");
    } else if (method.auth === "bearer" || method.auth === "key") {
      const token = await vscode.window.showInputBox({ title: method.auth === "key" ? "Engine API key" : "Engine access token", password: true,
        ignoreFocusOut: true, validateInput: input => !input.trim() || /\s/.test(input) ? "Enter a credential without whitespace." : undefined });
      if (token === undefined) return;
      if (method.auth === "key") credentials.apiKey = token; else credentials.authorization = "Bearer " + token;
    }
    if (method.auth === "basic" || method.auth === "bearer") {
      const extra = await vscode.window.showInputBox({ title: "Additional engine API key (optional)", password: true,
        prompt: "Leave empty unless this engine requires both application authentication and X-Api-Key", ignoreFocusOut: true,
        validateInput: input => /\s/.test(input) ? "Enter a key without whitespace." : undefined });
      if (extra === undefined) return;
      if (extra) credentials.apiKey = extra;
    }
    new EngineClient(endpoint.href, credentials); // Reject unsafe transport before storing any credential.
    await context.secrets.store(SECRET + endpoint.href, JSON.stringify(credentials));
    await config.update("testEngineUrl", endpoint.href, vscode.ConfigurationTarget.Global);
    void vscode.window.showInformationMessage("Test engine connection saved. Runs execute real tasks on this engine.");
  } catch (error) { showError(error); }
}

export function parseRunVariables(text: string): Record<string, unknown> {
  let variables: unknown;
  try { variables = JSON.parse(text); } catch { throw new Error("Input variables must be valid JSON."); }
  if (!variables || typeof variables !== "object" || Array.isArray(variables)) throw new Error("Input variables must be a JSON object.");
  return variables as Record<string, unknown>;
}

export class GraphExecutionController {
  private client?: EngineClient;
  private opened = false;
  private ready = false;
  private outbox: unknown[] = [];
  private session?: ExecutionSession;
  private disposed = false;
  private submitting = false;
  private pending?: TestRunRequest;
  private startRequest?: AbortController;
  private cancellation?: ExecutionCancellation;
  private stopRequest?: AbortController;
  private stopping = false;

  constructor(private readonly context: vscode.ExtensionContext, private readonly panel: vscode.WebviewPanel,
              private readonly document: vscode.TextDocument, private readonly flushEdits: () => Promise<void>) {
    panel.onDidDispose(() => this.dispose());
    panel.onDidChangeViewState(event => { if (!this.stopping) this.session?.setVisible(event.webviewPanel.visible); });
  }

  dispose(): void {
    this.disposed = true; this.outbox = []; this.session?.dispose(); this.startRequest?.abort(); this.stopRequest?.abort();
  }

  async open(id?: string): Promise<void> {
    try {
      requireTrust();
      if (this.disposed) return;
      if (this.stopping) throw new Error("Wait for cancellation verification before opening another run.");
      if (id && this.pending) throw new Error("Resolve the pending request before opening another execution.");
      if (!this.client || id || !this.session && !this.pending) this.client = await configuredClient(this.context);
      if (this.disposed) return;
      this.opened = true;
      this.post({ type: "executionOpen", endpoint: this.client!.endpoint.href });
      if (id) {
        await this.monitor(id);
      } else if (this.pending) {
        this.post({ type: "submissionError", id: this.pending.runId, uncertain: true,
          message: "Resolve the pending request before starting another run." });
      } else if (!this.submitting) {
        this.resetInputTemplate();
      }
    } catch (error) { showError(error); }
  }

  async message(message: any): Promise<void> {
    if (this.disposed) return;
    if (message?.type === "ready") {
      this.ready = true;
      for (const queued of this.outbox) this.post(queued);
      this.outbox = [];
      return;
    }
    if (!this.opened) return;
    try { await this.handle(message); }
    catch (error) { this.post({ type: "error", message: this.error(error) }); }
  }

  private async handle(message: any): Promise<void> {
    requireTrust();
    if (message?.type === "stopRun") { await this.stop(); return; }
    if (this.stopping && !["copyId"].includes(message?.type)) return;
    if (message?.type === "resetVariables" && !this.submitting && !this.pending) {
      this.resetInputTemplate();
    } else if (message?.type === "loadVariables" && !this.submitting && !this.pending) {
      const chosen = await vscode.window.showOpenDialog({ canSelectMany: false, filters: { JSON: ["json"] }, title: "Load execution variables" });
      if (!chosen?.[0]) return;
      const stat = await vscode.workspace.fs.stat(chosen[0]);
      if (stat.size > 1_048_576) throw new Error("The input file is too large (maximum 1 MiB).");
      const text = Buffer.from(await vscode.workspace.fs.readFile(chosen[0])).toString("utf8");
      parseRunVariables(text);
      this.post({ type: "variables", value: text });
    } else if (message?.type === "startRun" && this.document && !this.submitting && !this.pending) {
      const variables = parseRunVariables(String(message.variables ?? ""));
      await this.flushEdits();
      if (this.disposed || this.pending || this.submitting) return;
      const text = this.document.getText(); // Capture unsaved edits after preceding graph changes settle.
      const parsed = parseRunWorkflow(text);
      this.pending = { runId: randomUUID(), definition: parsed, variables };
      this.post({ type: "executionSnapshot", id: this.pending.runId, definition: parsed });
      await this.submit();
    } else if (message?.type === "retrySubmission" && this.pending && !this.submitting) {
      await this.submit(); // Same runId and immutable payload: never create a second run on a retry.
    } else if (message?.type === "checkSubmission" && this.pending && !this.submitting) {
      const id = this.pending.runId;
      try {
        this.startRequest = new AbortController();
        await this.client!.graph(id, this.startRequest.signal);
        if (this.disposed) return;
        this.pending = undefined; await this.monitor(id);
      } catch (error) {
        this.post({ type: "submissionError", id, uncertain: true, message: this.error(error) });
      }
    } else if (message?.type === "navigate" && this.session && typeof message.id === "string") {
      const state = this.session.state;
      const allowed = new Set([state.rootId, state.graph?.parentProcessId, state.graph?.process.continuedAs,
        state.graph?.process.continuedFrom, ...state.history.map(entry => entry.id), ...(state.children?.content.map(child => child.id) ?? [])]);
      if (allowed.has(message.id)) await this.session.navigate(message.id, message.follow === true);
    } else if (message?.type === "selectStep" && this.session) {
      await this.session.selectStep(typeof message.stepId === "string" ? message.stepId : undefined);
    } else if (message?.type === "childrenPage" && this.session) {
      await this.session.page(message.page);
    } else if (message?.type === "follow" && this.session) {
      await this.session.follow();
    } else if (message?.type === "refresh" && this.session) {
      await this.session.refresh();
    } else if (message?.type === "openExecution") {
      await vscode.commands.executeCommand("eventconductor.openExecution");
    } else if (message?.type === "copyId" && this.session) {
      await vscode.env.clipboard.writeText(this.session.state.currentId);
    }
  }

  private resetInputTemplate(): void {
    if (!this.document) return;
    const definition = parseRunWorkflow(this.document.getText());
    this.post({ type: "variables", value: JSON.stringify(workflowInputTemplate(definition), null, 2) });
  }

  private async submit(): Promise<void> {
    if (!this.pending) return;
    this.submitting = true; this.startRequest = new AbortController();
    this.post({ type: "submitting", id: this.pending.runId });
    const request = this.pending;
    try {
      await this.remember(request.runId);
      requireTrust();
      const process = await this.client!.start(request, this.startRequest.signal);
      if (!this.disposed) { this.pending = undefined; await this.monitor(process.id); }
    } catch (error) {
      const uncertain = !(error instanceof EngineError) || !error.status || error.status >= 500;
      this.post({ type: "submissionError", id: request.runId, uncertain, message: this.error(error) });
      if (!uncertain) this.pending = undefined;
    } finally { this.submitting = false; }
  }

  private async monitor(id: string): Promise<void> {
    this.session?.dispose();
    this.cancellation = new ExecutionCancellation(this.client!, state => this.post({ type: "cancellation", state }));
    this.post({ type: "cancellation", state: this.cancellation.state });
    this.session = new ExecutionSession(this.client!, id, state => this.post({ type: "execution", state }));
    this.session.setVisible(this.panel.visible);
    await this.session.refresh();
  }

  private async stop(): Promise<void> {
    if (!this.session || !this.cancellation || this.stopping || this.submitting || this.pending) return;
    const session = this.session;
    const state = session.state;
    if (!state.following || !state.graph || state.graph.process.id !== state.currentId) return;
    if (!["PENDING", "RUNNING", "PAUSED"].includes(state.graph.process.status) && !state.graph.process.continuedAs) return;
    const targetId = state.currentId;
    this.stopping = true; this.stopRequest = new AbortController();
    try {
      await session.suspend();
      if (this.disposed) return;
      requireTrust();
      // The host chooses the ID; webview payloads cannot cancel arbitrary processes.
      const id = await this.cancellation.stop(targetId, true, this.stopRequest.signal);
      if (!this.disposed && id !== session.state.currentId) await session.navigate(id, true);
    } finally {
      this.stopping = false;
      if (!this.disposed) session.setVisible(this.panel.visible);
    }
  }

  private async remember(id: string): Promise<void> {
    const previous = this.context.workspaceState.get<RecentRun[]>(HISTORY, []);
    const entry = { id, endpoint: this.client!.endpoint.href, name: this.document ? vscode.workspace.asRelativePath(this.document.uri) : id };
    await this.context.workspaceState.update(HISTORY, [entry, ...previous.filter(run => run.id !== id || run.endpoint !== entry.endpoint)].slice(0, 20));
  }
  private post(message: unknown): void {
    if (this.disposed) return;
    if (!this.ready) { this.outbox.push(message); return; }
    void this.panel.webview.postMessage(message);
  }
  private error(error: unknown): string { return error instanceof Error ? error.message : "Execution request failed."; }

}

/** Execution tools belong to the definition editor; it contains exactly one graph element. */
export function executionMarkup(): string {
  return `<section id="execution-dock" hidden aria-label="Workflow execution">
    <header class="execution-header"><strong>Execution</strong><span id="connection"></span>
      <button id="draft-view" type="button">Edit draft</button><button id="snapshot-view" type="button" disabled>Executed snapshot</button>
      <button id="stop" type="button" class="danger" disabled title="Cancel the execution shown here and its child workflows on the engine">Stop execution</button><button id="open-execution" type="button">Open execution…</button><button id="new-run" type="button">New run</button><button id="collapse-execution" type="button" aria-label="Collapse execution panel">Collapse</button></header>
    <div id="summary" role="status"></div><div id="cancellation-status" role="status" hidden></div><div id="draft-notice" role="status"></div>
    <div id="execution-error" role="alert" hidden></div><div id="warnings" role="status" hidden></div>
    <section id="input" hidden><label for="variables">Input variables (JSON)</label>
      <textarea id="variables" spellcheck="false">{}</textarea>
      <div class="actions"><span>Fill in the blank values; add any other inputs required by your workers.</span>
        <button id="reset">Reset template</button><button id="load">Load JSON…</button><button id="run" class="primary">Execute workflow</button></div>
    </section>
    <section id="uncertain" hidden><p>The request may already have started. Reuse it to avoid a second execution.</p>
      <button id="check">Check execution</button><button id="retry">Retry same request</button></section>
    <section id="monitor" hidden><nav id="navigation" aria-label="Execution navigation"></nav>
      <div class="tabs" role="tablist"><button role="tab" data-tab="steps" aria-selected="true">Steps</button>
        <button role="tab" data-tab="data" aria-selected="false">Variables</button><button role="tab" data-tab="logs" aria-selected="false">Logs</button>
        <button role="tab" data-tab="related" aria-selected="false">Children &amp; history</button></div>
      <section id="steps" class="detail"></section><section id="data" class="detail" hidden></section><section id="logs" class="detail" hidden></section>
      <section id="related" class="detail" hidden><h2>Child processes</h2><div id="children"></div><div id="paging"></div><h2>Execution history</h2><div id="history"></div></section>
    </section></section>`;
}

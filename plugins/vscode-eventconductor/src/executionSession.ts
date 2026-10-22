import { ChildrenPage, EngineClient, EngineError, GraphView, LogEntry, ProcessView, processId } from "./engineClient";

export interface ExecutionState {
  rootId: string; currentId: string; following: boolean; loading: boolean; selectedStep?: string;
  graph?: GraphView; children?: ChildrenPage; logs: LogEntry[];
  history: RunSummary[]; error?: string; warnings: string[];
}
type RunSummary = Pick<ProcessView, "id" | "definitionId" | "definitionVersion" | "name" | "status" | "continuedFrom" | "continuedAs">;
export type MonitoringClient = Pick<EngineClient, "graph" | "children" | "logs">;
const TERMINAL = new Set(["COMPLETED", "ERROR", "CANCELLED", "COMPENSATED", "COMPENSATION_FAILED"]);

/** One visible execution view: serialized reads, bounded lazy navigation, no mutation commands. */
export class ExecutionSession {
  readonly state: ExecutionState;
  private visible = true;
  private disposed = false;
  private timer?: ReturnType<typeof setTimeout>;
  private request?: AbortController;
  private generation = 0;
  private inFlight?: Promise<void>;
  private childPage = 0;
  private delay = 2000;

  constructor(private readonly client: MonitoringClient, rootId: string,
              private readonly publish: (state: ExecutionState) => void,
              private readonly interval = 2000) {
    this.state = { rootId: processId(rootId), currentId: rootId, following: true, loading: false, logs: [], history: [], warnings: [] };
  }

  async navigate(id: string, follow = false): Promise<void> {
    processId(id);
    ++this.generation;
    this.request?.abort();
    if (this.timer) clearTimeout(this.timer);
    this.state.currentId = id; this.state.following = follow;
    this.state.selectedStep = undefined; this.childPage = 0;
    this.state.graph = undefined; this.state.children = undefined; this.state.logs = [];
    if (this.inFlight) await this.inFlight;
    return this.refresh();
  }
  async selectStep(stepId?: string): Promise<void> {
    if (stepId && !this.state.graph?.definition.steps.some(step => step.id === stepId)) return;
    this.state.selectedStep = stepId; this.childPage = 0;
    return this.restartReads();
  }
  async page(page: number): Promise<void> {
    if (!Number.isInteger(page) || page < 0 || page >= (this.state.children?.totalPages ?? 1)) return;
    this.childPage = page; return this.restartReads();
  }
  async follow(): Promise<void> { this.state.following = true; return this.restartReads(); }
  setVisible(visible: boolean): void {
    this.visible = visible;
    if (!visible) { if (this.timer) clearTimeout(this.timer); this.request?.abort(); }
    else { void this.restartReads(); }
  }
  /** Pause monitoring reads while the host sends and verifies an operation. */
  async suspend(): Promise<void> {
    this.setVisible(false);
    if (this.inFlight) await this.inFlight;
  }
  dispose(): void {
    this.disposed = true; ++this.generation;
    if (this.timer) clearTimeout(this.timer);
    this.request?.abort();
  }

  private async restartReads(): Promise<void> {
    ++this.generation; this.request?.abort();
    if (this.timer) clearTimeout(this.timer);
    if (this.inFlight) await this.inFlight;
    return this.refresh();
  }

  refresh(): Promise<void> {
    if (this.disposed || !this.visible) return Promise.resolve();
    if (this.inFlight) return this.inFlight;
    if (this.timer) clearTimeout(this.timer);
    const operation = this.poll();
    this.inFlight = operation;
    void operation.finally(() => { if (this.inFlight === operation) this.inFlight = undefined; });
    return operation;
  }

  private async poll(): Promise<void> {
    const generation = this.generation;
    const request = new AbortController(); this.request = request;
    this.state.loading = true; this.emit();
    try {
      const visited = new Set<string>();
      let graph: GraphView;
      let id = this.state.currentId;
      for (let hops = 0; ; hops++) {
        if (visited.has(id)) throw new EngineError("The execution contains a circular continuation link.");
        visited.add(id);
        graph = await this.client.graph(id, request.signal);
        if (!this.current(generation, request)) return;
        if (id !== this.state.currentId) {
          this.state.selectedStep = undefined; this.childPage = 0;
          this.state.children = undefined; this.state.logs = [];
        }
        this.state.currentId = id;
        this.state.graph = graph;
        this.remember(graph.process);
        if (!this.state.following || !graph.process.continuedAs || hops >= 19) break;
        id = processId(graph.process.continuedAs);
      }
      const [children, logs] = await Promise.allSettled([
        this.client.children(this.state.currentId, this.childPage, this.state.selectedStep, request.signal),
        this.client.logs(this.state.currentId, request.signal),
      ]);
      if (!this.current(generation, request)) return;
      this.state.graph = graph; this.state.warnings = []; this.state.error = undefined;
      if (children.status === "fulfilled") this.state.children = children.value;
      else { this.state.children = undefined; this.state.warnings.push(`Child processes: ${this.message(children.reason)}`); }
      if (logs.status === "fulfilled") this.state.logs = logs.value;
      else this.state.warnings.push(`Logs: ${this.message(logs.reason)}`);
      this.delay = this.interval;
    } catch (error) {
      if (!this.current(generation, request)) return;
      this.state.error = error instanceof EngineError && error.status === 404
        ? "Execution unavailable: it may have been purged, or a new continuation may not be visible yet. Refresh to check again."
        : this.message(error);
      this.delay = Math.min(Math.max(this.interval, this.delay * 2), 30_000);
    } finally {
      if (this.current(generation, request)) {
        this.state.loading = false; this.emit();
        const process = this.state.graph?.process;
        if (this.state.error || !process || !TERMINAL.has(process.status) || this.state.following && process.continuedAs) {
          this.timer = setTimeout(() => { void this.refresh(); }, this.delay);
        }
      }
    }
  }
  private current(generation: number, request: AbortController): boolean {
    return !this.disposed && this.visible && generation === this.generation && !request.signal.aborted;
  }
  private remember(process: ProcessView): void {
    const { id, definitionId, definitionVersion, name, status, continuedFrom, continuedAs } = process;
    this.state.history = [...this.state.history.filter(entry => entry.id !== id),
      { id, definitionId, definitionVersion, name, status, continuedFrom, continuedAs }].slice(-200);
  }
  private emit(): void { if (!this.disposed && this.visible) this.publish({ ...this.state, history: [...this.state.history] }); }
  private message(error: unknown): string { return error instanceof Error ? error.message : "Request failed."; }
}

import { EngineClient, EngineError, GraphView, processId } from "./engineClient";

export type CancellationPhase = "idle" | "requesting" | "accepted" | "confirmed" | "review" | "finished" | "unknown" | "failed";
export interface CancellationState { phase: CancellationPhase; id?: string; message: string; pending: boolean; }
type Client = Pick<EngineClient, "graph" | "cancel">;
const TERMINAL = new Set(["COMPLETED", "ERROR", "COMPENSATED", "COMPENSATION_FAILED"]);

/** A stop is a server command followed by verification, never a local state change. */
export class ExecutionCancellation {
  state: CancellationState = { phase: "idle", message: "", pending: false };
  private operation?: Promise<string>;
  get busy(): boolean { return !!this.operation; }

  constructor(private readonly client: Client, private readonly publish: (state: CancellationState) => void,
              private readonly options: { timeoutMs: number; pollMs: number; retryMs?: number } = { timeoutMs: 30_000, pollMs: 1000 }) {}

  stop(id: string, follow: boolean, signal: AbortSignal): Promise<string> {
    if (this.operation) return this.operation;
    const operation = this.run(processId(id), follow, signal);
    this.operation = operation;
    void operation.finally(() => {
      if (this.operation === operation) this.operation = undefined;
      this.state = { ...this.state, pending: false }; this.publish({ ...this.state });
    });
    return operation;
  }

  private async run(initialId: string, follow: boolean, signal: AbortSignal): Promise<string> {
    let id = initialId, sent = false, uncertain = false, conflicts = 0, attempts = 0, lastSentAt = 0;
    const visited = new Set([id]);
    const deadline = Date.now() + this.options.timeoutMs;
    this.update("requesting", id, "Requesting cancellation on the engine…");
    try {
      do {
        if (signal.aborted) throw new EngineError("Cancellation verification interrupted.");
        const graph = await this.client.graph(id, signal);
        if (follow && graph.process.continuedAs) {
          id = processId(graph.process.continuedAs);
          if (visited.has(id) || visited.size >= 20) throw new EngineError("Cannot safely follow this continuation chain to cancel it.");
          visited.add(id); sent = false; uncertain = false; conflicts = 0; attempts = 0; lastSentAt = 0;
          this.update("requesting", id, "Following the current continuation to stop it…");
          continue;
        }
        if (graph.process.status === "CANCELLED") {
          this.update("confirmed", id, "Cancellation confirmed by the engine. Pending steps are cancelled; workers have been asked to stop.");
          return id;
        }
        if (TERMINAL.has(graph.process.status)) {
          this.update("finished", id, `The execution ended as ${graph.process.status}; it was not cancelled.`);
          return id;
        }
        if (sent && this.needsReview(graph)) {
          this.update("review", id, "The engine paused this execution for agent review. Cancellation is not yet confirmed; resolve the review in the engine.");
          return id;
        }
        // The engine command is idempotent. An acknowledged async command can still roll
        // back later; retry only after another read proves this same execution is active.
        if (!sent || attempts < 3 && Date.now() - lastSentAt >= (this.options.retryMs ?? 5000)) {
          attempts++; lastSentAt = Date.now();
          try {
            await this.client.cancel(id, signal);
            this.update("accepted", id, "Cancellation requested. Waiting for the engine to confirm CANCELLED…");
          } catch (error) {
            if (error instanceof EngineError && error.status === 409 && ++conflicts <= 2) continue;
            if (error instanceof EngineError && error.status && error.status < 500) throw error;
            uncertain = true;
            this.update("unknown", id, "Connection interrupted. Checking whether the engine accepted the cancellation…");
          }
          sent = true;
        }
        if (Date.now() >= deadline) break;
        await this.wait(signal);
      } while (Date.now() < deadline);
      this.update("unknown", id, "Cancellation has not been confirmed. The execution may still be running; refresh or retry Stop execution.");
    } catch (error) {
      const denied = error instanceof EngineError && !!error.status && error.status < 500;
      this.update(sent || uncertain ? "unknown" : denied ? "failed" : "unknown", id,
        `${error instanceof Error ? error.message : "Cancellation failed."} Cancellation is not confirmed.`);
    }
    return id;
  }

  private needsReview(graph: GraphView): boolean {
    return graph.process.status === "PAUSED" && graph.steps.some(step => step.agent?.underReview);
  }
  private update(phase: CancellationPhase, id: string, message: string): void {
    this.state = { phase, id, message, pending: true }; this.publish({ ...this.state });
  }
  private wait(signal: AbortSignal): Promise<void> {
    return new Promise((resolve, reject) => {
      const done = () => { signal.removeEventListener("abort", abort); resolve(); };
      const timer = setTimeout(done, this.options.pollMs);
      const abort = () => { clearTimeout(timer); signal.removeEventListener("abort", abort); reject(new EngineError("Cancellation verification interrupted.")); };
      signal.addEventListener("abort", abort, { once: true });
      if (signal.aborted) abort();
    });
  }
}

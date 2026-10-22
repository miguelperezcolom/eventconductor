import * as http from "node:http";
import * as https from "node:https";

export interface EngineCredentials { authorization?: string; apiKey?: string; }
export interface ProcessView {
  id: string; definitionId: string; definitionVersion: number; name: string; status: string;
  variables: Record<string, string>; continuedFrom?: string | null; continuedAs?: string | null;
  created?: string; started?: string; finished?: string;
}
export interface StepView {
  id: string; stepId: string; status: string; attemptCount: number; workerId?: string;
  iteration: number; itemIndex?: number; parentExecutionId?: string; superseded?: boolean;
  startedAt?: string; finishedAt?: string; deadlineAt?: string;
  agent?: { invocationId: string; underReview: boolean; holdReason?: string; lastStateSequence?: number };
}
export interface GraphView {
  process: ProcessView; testRun: boolean; parentProcessId?: string | null;
  definition: { id?: string; name?: string; steps: Array<{ id: string; name?: string; type: string }>; [key: string]: unknown };
  overlay: Record<string, unknown>; steps: StepView[];
}
export interface ChildView {
  id: string; parentStepExecutionId: string; stepId: string; itemIndex?: number | null; iteration: number;
  definitionId: string; definitionVersion: number; name: string; status: string;
  continuedFrom?: string | null; continuedAs?: string | null;
}
export interface ChildrenPage { content: ChildView[]; page: number; size: number; totalElements: number; totalPages: number; }
export interface LogEntry { timestamp?: string; stepExecutionId?: string; type: string; message: string; workerId?: string; }
export interface TestRunRequest { runId: string; definition: Record<string, unknown>; variables: Record<string, unknown>; }

export class EngineError extends Error {
  constructor(message: string, public readonly status?: number) { super(message); this.name = "EngineError"; }
}

export function engineEndpoint(value: string): URL {
  let endpoint: URL;
  try { endpoint = new URL(value); } catch { throw new Error("Enter a full HTTP(S) test engine URL."); }
  if (!["https:", "http:"].includes(endpoint.protocol) || endpoint.username || endpoint.password || endpoint.hash || endpoint.search) {
    throw new Error("Use an HTTP(S) engine URL without credentials, query parameters or a fragment.");
  }
  endpoint.pathname = endpoint.pathname.replace(/\/workflow\/api\/?$/, "").replace(/\/+$/, "") + "/";
  return endpoint;
}

export function processId(value: unknown): string {
  if (typeof value !== "string" || !/^[A-Za-z0-9][A-Za-z0-9._:-]{0,254}$/.test(value)) {
    throw new Error("Invalid execution identifier.");
  }
  return value;
}

/** Extension-host transport. A webview can never choose a URL, header or HTTP method. */
export class EngineClient {
  readonly endpoint: URL;
  constructor(value: string, private readonly credentials: EngineCredentials = {},
              private readonly limits: { timeoutMs?: number; maxBytes?: number } = {}) {
    this.endpoint = engineEndpoint(value);
    const loopback = ["localhost", "127.0.0.1", "[::1]"].includes(this.endpoint.hostname);
    if ((credentials.authorization || credentials.apiKey) && this.endpoint.protocol !== "https:" && !loopback) {
      throw new Error("Engine authentication requires HTTPS, except on localhost.");
    }
    if (Object.values(credentials).some(value => value && /[\r\n]/.test(value))) throw new Error("Invalid engine credential.");
  }

  async start(request: TestRunRequest, signal?: AbortSignal): Promise<ProcessView> {
    const result = await this.request<ProcessView>("POST", "test-runs", request, signal);
    if (processId(result?.id) !== request.runId) throw new EngineError("The engine returned another execution identifier.");
    return result;
  }
  async cancel(id: string, signal?: AbortSignal): Promise<void> {
    processId(id);
    const result = await this.request<{ processId: string; command: string }>("POST", `processes/${encodeURIComponent(id)}/cancel`, undefined, signal);
    if (result?.processId !== id || result?.command !== "cancel") throw new EngineError("The engine returned an invalid cancellation acknowledgement.");
  }
  async graph(id: string, signal?: AbortSignal): Promise<GraphView> {
    const graph = await this.request<GraphView>("GET", `processes/${encodeURIComponent(processId(id))}/graph`, undefined, signal);
    if (graph?.process?.id !== id || !Array.isArray(graph?.definition?.steps) || !Array.isArray(graph?.steps)) {
      throw new EngineError("The engine returned an invalid execution graph.");
    }
    for (const link of [graph.process.continuedAs, graph.process.continuedFrom, graph.parentProcessId]) if (link) processId(link);
    return graph;
  }
  async children(id: string, page: number, stepId?: string, signal?: AbortSignal): Promise<ChildrenPage> {
    if (!Number.isInteger(page) || page < 0) throw new Error("Invalid child page.");
    const query = `?page=${page}&size=20` + (stepId ? `&stepId=${encodeURIComponent(stepId)}` : "");
    const result = await this.request<ChildrenPage>("GET", `processes/${encodeURIComponent(processId(id))}/children${query}`, undefined, signal);
    if (!Array.isArray(result?.content) || !Number.isInteger(result.totalPages) || result.totalPages < 0 || result.content.length > 100) {
      throw new EngineError("The engine returned an invalid child-process page.");
    }
    for (const child of result.content) processId(child.id);
    return result;
  }
  async logs(id: string, signal?: AbortSignal): Promise<LogEntry[]> {
    const result = await this.request<LogEntry[]>("GET", `processes/${encodeURIComponent(processId(id))}/logs`, undefined, signal);
    if (!Array.isArray(result)) throw new EngineError("The engine returned invalid execution logs.");
    return result;
  }

  private request<T>(method: "GET" | "POST", path: string, body?: unknown, signal?: AbortSignal): Promise<T> {
    const endpoint = new URL(`workflow/api/${path}`, this.endpoint);
    const data = body === undefined ? undefined : Buffer.from(JSON.stringify(body), "utf8");
    if (data && data.length > (this.limits.maxBytes ?? 4_194_304)) return Promise.reject(new EngineError("Test input is too large."));
    return new Promise((resolve, reject) => {
      if (signal?.aborted) { reject(new EngineError("Request cancelled.")); return; }
      const headers: Record<string, string> = { Accept: "application/json" };
      if (this.credentials.authorization) headers.Authorization = this.credentials.authorization;
      if (this.credentials.apiKey) headers["X-Api-Key"] = this.credentials.apiKey;
      if (data) { headers["Content-Type"] = "application/json"; headers["Content-Length"] = String(data.length); }
      let settled = false;
      let timer: ReturnType<typeof setTimeout> | undefined;
      const finish = (error?: Error, result?: T) => {
        if (settled) return;
        settled = true;
        if (timer) clearTimeout(timer);
        signal?.removeEventListener("abort", abort);
        if (error) reject(error); else resolve(result as T);
      };
      const request = (endpoint.protocol === "https:" ? https : http).request(endpoint, { method, headers }, response => {
        const status = response.statusCode ?? 0;
        if (status >= 300 && status < 400) {
          finish(new EngineError("Engine redirects are not followed. Configure the final engine URL.", status));
          response.destroy(); return;
        }
        const chunks: Buffer[] = [];
        let bytes = 0;
        response.on("data", (chunk: Buffer) => {
          bytes += chunk.length;
          if (bytes > (this.limits.maxBytes ?? 4_194_304)) {
            finish(new EngineError("Engine response is too large. Open the engine console for the complete history."));
            response.destroy(); request.destroy(); return;
          }
          chunks.push(chunk);
        });
        response.on("error", () => finish(new EngineError("The engine connection was interrupted.")));
        response.on("end", () => {
          let result: any;
          try { result = JSON.parse(Buffer.concat(chunks).toString("utf8")); } catch { /* non-JSON errors remain generic */ }
          if (status === 403 && path === "test-runs") {
            finish(new EngineError("The engine refused the test run. The connection's identity needs a role listed in workflow.test-runs.required-roles.", status));
          } else if (status === 401 || status === 403) {
            finish(new EngineError("Engine access denied. Check the connection credentials and workflow permissions.", status));
          } else if (status === 404 && path === "test-runs") {
            finish(new EngineError("Test runs are unavailable. Update the engine and enable workflow.test-runs.enabled=true.", status));
          } else if (status < 200 || status >= 300) {
            finish(new EngineError(typeof result?.detail === "string" ? result.detail : `Engine request failed (HTTP ${status}).`, status));
          } else if (result === undefined) {
            finish(new EngineError("The engine did not return JSON."));
          } else { finish(undefined, result as T); }
        });
      });
      const abort = () => { finish(new EngineError("Request cancelled.")); request.destroy(); };
      signal?.addEventListener("abort", abort, { once: true });
      timer = setTimeout(() => { finish(new EngineError("Engine request timed out. The execution may still be running.")); request.destroy(); }, this.limits.timeoutMs ?? 15_000);
      request.on("error", () => finish(new EngineError("Cannot reach the test engine. Check its address and availability.")));
      request.end(data);
    });
  }
}

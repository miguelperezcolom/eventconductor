window.ecExecutionBridge = ({ vscode, graph: graphElement, projection, applyGraph }) => {
  const byId = id => document.getElementById(id);
  const send = (type, extra = {}) => vscode.postMessage({ type, ...extra });
  let current, pendingId, cancellation = {phase:"idle",pending:false,message:""};
  let preparing = false;
  const dock = byId("execution-dock");
  const updateNotice = () => {
    const view = projection.view();
    byId("draft-notice").textContent = view.notice;
    byId("snapshot-view").disabled = !view.hasSnapshot;
    byId("snapshot-view").setAttribute("aria-pressed", String(view.mode === "snapshot"));
    byId("draft-view").setAttribute("aria-pressed", String(view.mode === "draft"));
  };
  const updateStop = () => {
    const process = current?.graph?.process;
    const eligible = current?.following && process?.id === current.currentId &&
      (["PENDING", "RUNNING", "PAUSED"].includes(process.status) || !!process.continuedAs);
    const available = !!eligible && !pendingId;
    byId("stop").disabled = !available || cancellation.pending;
    byId("stop").textContent = cancellation.pending ? "Stopping…" : "Stop execution";
    graphElement.hostExecutionActive = available;
    graphElement.hostExecutionStopping = !!cancellation.pending;
    for (const id of ["run", "reset", "load"]) byId(id).disabled = !!pendingId || cancellation.pending;
    byId("new-run").disabled = !!pendingId || cancellation.pending;
    byId("open-execution").disabled = cancellation.pending;
    for (const child of byId("navigation").children) child.disabled = cancellation.pending || child.getAttribute("data-default-disabled") === "true";
  };
  const paint = () => { applyGraph(); updateNotice(); updateStop(); };
  const stop = () => { if (!byId("stop").disabled) { dock.hidden = false; byId("stop").disabled = true; send("stopRun"); } };
  byId("stop").addEventListener("click", stop);
  graphElement.addEventListener("ec-stop-workflow", stop);
  byId("draft-view").addEventListener("click", () => { projection.showDraft(); paint(); });
  byId("snapshot-view").addEventListener("click", () => { projection.showSnapshot(); paint(); });
  byId("open-execution").addEventListener("click", () => send("openExecution"));
  byId("new-run").addEventListener("click", () => send("runWorkflow"));
  byId("collapse-execution").addEventListener("click", () => { projection.showDraft(); paint(); dock.hidden = true; });
  const button = (label, action, disabled = false) => {
    const el = document.createElement("button"); el.textContent = label; el.disabled = disabled; el.setAttribute("data-default-disabled", String(disabled)); el.addEventListener("click", action); return el;
  };
  const showError = message => { byId("execution-error").textContent = message || ""; byId("execution-error").hidden = !message; };
  const pre = value => { const el = document.createElement("pre"); el.textContent = typeof value === "string" ? value : JSON.stringify(value, null, 2); return el; };
  function table(headers, rows) {
    const el = document.createElement("table"), head = document.createElement("tr");
    for (const label of headers) { const th = document.createElement("th"); th.textContent = label; head.append(th); }
    const thead = document.createElement("thead"); thead.append(head); el.append(thead);
    const body = document.createElement("tbody");
    for (const row of rows) { const tr = document.createElement("tr"); for (const value of row) { const td = document.createElement("td"); td.textContent = value == null ? "—" : String(value); tr.append(td); } body.append(tr); }
    el.append(body); return el;
  }
  byId("run").addEventListener("click", () => { byId("run").disabled = true; showError(); send("startRun", { variables: byId("variables").value }); });
  byId("load").addEventListener("click", () => send("loadVariables"));
  byId("reset").addEventListener("click", () => send("resetVariables"));
  const lockInputs = locked => { for (const id of ["variables", "load", "reset"]) byId(id).disabled = locked; };
  byId("retry").addEventListener("click", () => send("retrySubmission"));
  byId("check").addEventListener("click", () => send("checkSubmission"));
  for (const tab of document.querySelectorAll("[data-tab]")) tab.addEventListener("click", () => {
    for (const other of document.querySelectorAll("[data-tab]")) { const selected = other === tab; other.setAttribute("aria-selected", String(selected)); byId(other.dataset.tab).hidden = !selected; }
  });
  graphElement.addEventListener("ec-step-selected", event => send("selectStep", { stepId: event.detail.stepId }));

  window.addEventListener("message", async event => {
    const message = event.data;
    if (message.type === "executionOpen") { dock.hidden = false; byId("connection").textContent = message.endpoint; }
    else if (message.type === "cancellation") {
      cancellation = message.state;
      byId("cancellation-status").textContent = cancellation.message + (cancellation.id ? ` (${cancellation.id})` : "");
      byId("cancellation-status").hidden = !cancellation.message;
      updateStop();
    }
    else if (message.type === "executionSnapshot") { projection.capture(message.id, message.definition); }
    else if (message.type === "variables") {
      preparing = true; byId("input").hidden = false; byId("variables").value = message.value;
      byId("run").disabled = false; lockInputs(false); showError(); projection.showDraft(); paint();
    }
    else if (message.type === "error") { showError(message.message); if (byId("uncertain").hidden) { byId("run").disabled = false; lockInputs(false); } }
    else if (message.type === "submitting") {
      pendingId = message.id; byId("new-run").disabled = true; byId("run").disabled = true; lockInputs(true); byId("uncertain").hidden = true; showError(); byId("summary").textContent = `Starting ${message.id}…`; updateStop();
    } else if (message.type === "submissionError") {
      pendingId = message.uncertain ? message.id : undefined; byId("new-run").disabled = !!message.uncertain; showError(message.message); byId("uncertain").hidden = !message.uncertain; byId("run").disabled = message.uncertain; lockInputs(message.uncertain); updateStop();
    } else if (message.type === "execution") {
      current = message.state;
      if (pendingId === current.currentId) {
        pendingId = undefined; preparing = false; byId("new-run").disabled = false;
        byId("uncertain").hidden = true;
      }
      if (!preparing) byId("input").hidden = true;
      byId("monitor").hidden = false;
      if (!pendingId && !preparing) showError(current.error);
      projection.update(current); paint();
      byId("warnings").textContent = current.warnings.join("\n"); byId("warnings").hidden = !current.warnings.length;
      const graph = current.graph;
      const nav = byId("navigation"); nav.replaceChildren();
      nav.append(button("Initial run", () => send("navigate", { id: current.rootId, follow: false })),
        button("Follow current execution", () => send("follow"), current.following), button("Refresh", () => send("refresh")), button("Copy execution ID", () => send("copyId")));
      if (graph?.parentProcessId) nav.append(button("Parent process", () => send("navigate", { id: graph.parentProcessId, follow: false })));
      if (graph?.process.continuedFrom) nav.append(button("Previous run", () => send("navigate", { id: graph.process.continuedFrom, follow: false })));
      if (graph?.process.continuedAs) nav.append(button("Next run", () => send("navigate", { id: graph.process.continuedAs, follow: true })));
      byId("summary").textContent = graph ? `${graph.process.name} · version ${graph.process.definitionVersion} · ${graph.process.status} · ${current.currentId}${current.loading ? " · refreshing…" : ""}${graph.testRun ? " · test run" : ""}` : `Loading ${current.currentId}…`;
      const history = byId("history"); history.replaceChildren();
      for (const run of [...current.history].reverse()) history.append(button(`${run.name} · v${run.definitionVersion} · ${run.status}\n${run.id}`, () => send("navigate", { id: run.id, follow: false }), run.id === current.currentId));
      const children = byId("children"); children.replaceChildren();
      if (current.selectedStep) children.append(button(`Step: ${current.selectedStep} · Show all`, () => send("selectStep")));
      for (const child of current.children?.content ?? []) {
        const item = child.itemIndex == null ? "" : ` · item ${child.itemIndex}`;
        children.append(button(`${child.stepId}${item} · run ${child.iteration + 1}\n${child.name} · v${child.definitionVersion} · ${child.status}`, () => send("navigate", { id: child.id, follow: true })));
      }
      if (!current.children?.content.length) { const empty = document.createElement("p"); empty.className = "muted"; empty.textContent = "No child processes on this page."; children.append(empty); }
      const paging = byId("paging"); paging.replaceChildren();
      if (current.children?.totalPages > 1) {
        const page = current.children.page;
        paging.append(button("Previous page", () => send("childrenPage", { page: page - 1 }), page === 0),
          button(`Page ${page + 1}/${current.children.totalPages}`, () => {}, true),
          button("Next page", () => send("childrenPage", { page: page + 1 }), page + 1 >= current.children.totalPages));
      }
      if (!graph) { for (const id of ["steps", "data", "logs"]) byId(id).replaceChildren(); updateStop(); return; }
      const steps = current.selectedStep ? graph.steps.filter(step => step.stepId === current.selectedStep) : graph.steps;
      byId("steps").replaceChildren(table(["Step", "Execution", "State", "Item / pass", "Retries", "Worker", "Started", "Finished / deadline", "Agent review"], steps.map(step => [
        step.stepId, step.id, step.status + (step.superseded ? " (history)" : ""), `${step.itemIndex ?? "—"} / ${step.iteration + 1}`,
        step.attemptCount, step.workerId, step.startedAt, step.finishedAt || step.deadlineAt,
        step.agent ? `${step.agent.invocationId}${step.agent.underReview ? " · review required" : ""}${step.agent.holdReason ? " · " + step.agent.holdReason : ""}` : "",
      ])));
      byId("data").replaceChildren(pre(graph.process.variables || {}));
      const logs = current.selectedStep ? current.logs.filter(log => steps.some(step => step.id === log.stepExecutionId)) : current.logs;
      updateStop();
      byId("logs").replaceChildren(table(["Time", "Step execution", "Type", "Message"], logs.map(log => [log.timestamp, log.stepExecutionId, log.type, log.message])));
    }
  });
  return { updateNotice };
};

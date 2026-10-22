// Webview bridge between VS Code and the <eventconductor-workflow-graph> component.
// The component's `value` is always JSON; the extension handles YAML <-> JSON at the file boundary.
(function () {
  const vscode = acquireVsCodeApi();
  const el = document.querySelector("eventconductor-workflow-graph");
  const errorBox = document.getElementById("error");

  // Follow VS Code's light/dark theme.
  const applyTheme = () => {
    const dark = document.body.classList.contains("vscode-dark") ||
      document.body.classList.contains("vscode-high-contrast");
    if (dark) el.setAttribute("dark", ""); else el.removeAttribute("dark");
  };
  new MutationObserver(applyTheme).observe(document.body, { attributes: true, attributeFilter: ["class"] });

  let applying = false;
  const projection = window.ecCreateGraphProjection();
  const applyGraph = () => {
    const view = projection.view();
    el.readOnly = view.readOnly;
    el.overlay = view.overlay;
    if (el.getAttribute("value") !== view.value) {
      applying = true;
      el.setAttribute("value", view.value);
      setTimeout(() => { applying = false; }, 0);
    }
    return view;
  };
  const execution = window.ecExecutionBridge({ vscode, graph: el, projection, applyGraph });

  window.addEventListener("message", (event) => {
    const msg = event.data;
    if (!msg) return;
    if (msg.type === "setValue") {
      errorBox.style.display = "none";
      projection.setDraft(msg.value);
      applyGraph();
      execution.updateNotice();
    } else if (msg.type === "requestExport") {
      // The "Export Graph as SVG" command: same path as the graph's own export button, which
      // ends in the `ec-export-svg` listener below.
      if (typeof el.requestExport === "function") el.requestExport();
    } else if (msg.type === "parseError") {
      errorBox.textContent = "Could not parse this .ec file: " + msg.message;
      errorBox.style.display = "block";
    } else if (msg.type === "agentCatalogue") {
      customElements.whenDefined("eventconductor-workflow-graph").then(() => {
        el.setAgentCatalogue(msg.entries, msg.error);
      });
    } else if (msg.type === "reloadAgentCatalogue") {
      vscode.postMessage({ type: "requestAgentCatalogue" });
    }
  });

  // The component fires `value-changed` (JSON) whenever the user edits the graph.
  el.addEventListener("value-changed", (e) => {
    if (applying || el.readOnly) return;
    projection.setDraft(e.detail.value);
    vscode.postMessage({ type: "edit", value: e.detail.value });
    // Polls change only the overlay while editing: preserve selection, layout and property focus.
    el.overlay = projection.view().overlay;
    execution.updateNotice();
  });

  // The host binds execution to this editor's document, never to a URI supplied by the webview.
  el.addEventListener("ec-run-workflow", () => vscode.postMessage({ type: "runWorkflow" }));

  // The extension host owns network access and credentials. The webview requests only a reload.
  el.addEventListener("ec-agent-catalogue-request", () => {
    vscode.postMessage({ type: "requestAgentCatalogue" });
  });

  // Exporting. The component would otherwise hand the file to the browser through a blob URL,
  // which this webview's CSP forbids outright, so we take the event and let the extension write
  // the file through VS Code's own save dialog.
  el.addEventListener("ec-export-svg", (e) => {
    e.preventDefault();
    vscode.postMessage({ type: "exportSvg", name: e.detail.name, svg: e.detail.svg });
  });

  applyTheme();
  vscode.postMessage({ type: "ready" });
})();

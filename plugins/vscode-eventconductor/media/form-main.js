// Webview bridge between VS Code and the <eventconductor-form-editor> component.
// The component's `value` is always JSON; the extension handles YAML <-> JSON at the file boundary.
(function () {
  const vscode = acquireVsCodeApi();
  const el = document.querySelector("eventconductor-form-editor");
  const errorBox = document.getElementById("error");

  // Follow VS Code's light/dark theme.
  const applyTheme = () => {
    const dark = document.body.classList.contains("vscode-dark") ||
      document.body.classList.contains("vscode-high-contrast");
    if (dark) el.setAttribute("dark", ""); else el.removeAttribute("dark");
  };
  new MutationObserver(applyTheme).observe(document.body, { attributes: true, attributeFilter: ["class"] });

  let applying = false;

  window.addEventListener("message", (event) => {
    const msg = event.data;
    if (!msg) return;
    if (msg.type === "setValue") {
      errorBox.style.display = "none";
      if (el.getAttribute("value") !== msg.value) {
        applying = true;
        el.setAttribute("value", msg.value);
        // release the guard after the property has been consumed
        setTimeout(() => { applying = false; }, 0);
      }
    } else if (msg.type === "parseError") {
      errorBox.textContent = "Could not parse this .ecform file: " + msg.message;
      errorBox.style.display = "block";
    }
  });

  // The component fires `value-changed` (JSON) whenever the user edits the form.
  el.addEventListener("value-changed", (e) => {
    if (applying) return; // this change came from us setting the value, not a user edit
    vscode.postMessage({ type: "edit", value: e.detail.value });
  });

  applyTheme();
  vscode.postMessage({ type: "ready" });
})();

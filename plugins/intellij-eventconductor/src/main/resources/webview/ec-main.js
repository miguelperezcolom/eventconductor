// Bridge between the IntelliJ (JCEF) host and the <eventconductor-workflow-graph> component.
// The component's `value` is always JSON; this page converts to/from the file's YAML or JSON
// (via js-yaml), so the Kotlin side only shuttles raw file text. Kotlin injects:
//   window.__ecSetFileB64(base64)  -> push the file text into the graph
//   window.__ecOnEdit(fileText)    -> receive an edited file text (installed by Kotlin)
//   window.__ecSetTheme(boolean)   -> follow the IDE light/dark theme
(function () {
  var el = document.querySelector("eventconductor-workflow-graph");
  var fileIsYaml = false;
  var applying = false;

  function looksLikeYaml(t) {
    var s = t.replace(/^\s+/, "");
    return !!s && s[0] !== "{" && s[0] !== "[";
  }

  function b64ToUtf8(b64) {
    var bin = atob(b64);
    var bytes = new Uint8Array(bin.length);
    for (var i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
    return new TextDecoder("utf-8").decode(bytes);
  }

  window.__ecSetTheme = function (dark) {
    if (dark) el.setAttribute("dark", ""); else el.removeAttribute("dark");
  };

  window.__ecSetFileB64 = function (b64) {
    var text = b64ToUtf8(b64);
    fileIsYaml = looksLikeYaml(text);
    var obj;
    try {
      obj = text.trim() ? (fileIsYaml ? window.jsyaml.load(text) : JSON.parse(text))
                        : { name: "New Workflow", steps: [] };
    } catch (e) {
      return; // leave the graph as-is on a parse error; the text editor shows the problem
    }
    var json = JSON.stringify(obj, null, 2);
    if (el.getAttribute("value") !== json) {
      applying = true;
      el.setAttribute("value", json);
      setTimeout(function () { applying = false; }, 0);
    }
  };

  el.addEventListener("value-changed", function (e) {
    if (applying) return; // came from us setting the value, not a user edit
    var obj;
    try { obj = JSON.parse(e.detail.value); } catch (_) { return; }
    var fileText = fileIsYaml
      ? window.jsyaml.dump(obj, { noRefs: true, lineWidth: 120 })
      : JSON.stringify(obj, null, 2) + "\n";
    if (window.__ecOnEdit) window.__ecOnEdit(fileText);
  });
})();

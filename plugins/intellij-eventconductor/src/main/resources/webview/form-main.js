// Bridge between the IntelliJ (JCEF) host and the <eventconductor-form-editor> component.
// The component's `value` is always JSON; this page converts to/from the file's YAML or JSON
// (via ec-yaml-edit.js, the same lossless write-back the VS Code extension uses: an edit changes
// only the lines it has to, keeping comments and quoting), so the Kotlin side only shuttles raw
// file text. Kotlin injects:
//   window.__ecSetFileB64(base64)  -> push the file text into the editor
//   window.__ecOnEdit(fileText)    -> receive an edited file text (installed by Kotlin)
//   window.__ecSetTheme(boolean)   -> follow the IDE light/dark theme
(function () {
  var el = document.querySelector("eventconductor-form-editor");
  var fileText = "";   // the file as last pushed by the IDE (or written by us): what edits apply to
  var applying = false;

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
    fileText = text;
    var obj;
    try {
      // An empty file is YAML: a new file the editor fills in comes out like the docs' examples.
      obj = text.trim() ? window.ecYamlEdit.parseDefinition(text)
                        : { name: "New Form", fields: [] };
    } catch (e) {
      return; // leave the editor as-is on a parse error; the text editor shows the problem
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
    var next = window.ecYamlEdit.applyDefinitionEdit(fileText, obj);
    if (next === fileText) return;
    fileText = next;
    if (window.__ecOnEdit) window.__ecOnEdit(next);
  });
})();

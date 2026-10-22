// Pure projection for the one canvas shared by the draft and execution snapshots.
(function (root) {
  function canonical(value) {
    if (Array.isArray(value)) return value.map(canonical);
    if (value && typeof value === "object") return Object.fromEntries(Object.keys(value).sort().filter(key => key !== "layout").map(key => [key, canonical(value[key])]));
    return value;
  }
  const signature = value => JSON.stringify(canonical(value));
  function createGraphProjection() {
    let draft = { name: "New Workflow", steps: [] }, state, mode = "draft", lastId, lastSnapshot;
    const baselines = new Map();
    return {
      setDraft(value) { draft = typeof value === "string" ? JSON.parse(value) : value; },
      capture(id, definition) {
        baselines.set(id, JSON.parse(JSON.stringify(definition)));
        if (baselines.size > 20) baselines.delete(baselines.keys().next().value);
      },
      update(next) {
        state = next;
        if (next.graph) lastSnapshot = next.graph.definition;
        if (next.currentId !== lastId) mode = baselines.has(next.currentId) ? "draft" : "snapshot";
        lastId = next.currentId;
      },
      showDraft() { mode = "draft"; },
      showSnapshot() { if (state?.graph) mode = "snapshot"; },
      view() {
        const graph = state?.graph;
        if (mode === "snapshot" && graph) return {
          value: JSON.stringify(graph.definition), overlay: JSON.stringify(graph.overlay || {}), readOnly: true,
          mode, hasSnapshot: true, notice: "Executed snapshot · read only. Return to Edit draft to keep building the workflow.",
        };
        if (mode === "snapshot" && state) return {
          value: JSON.stringify(lastSnapshot || {name:"Loading execution",steps:[]}), overlay:"{}", readOnly:true,
          mode, hasSnapshot:false, notice:state.error ? "Execution snapshot unavailable. Return to Edit draft to continue editing." : "Loading the selected execution snapshot…",
        };
        const baseline = baselines.get(state?.currentId), overlay = {};
        if (graph && baseline) {
          const steps = new Map((baseline.steps || []).map(step => [step.id, step]));
          for (const step of draft.steps || []) if (steps.has(step.id) && signature(step) === signature(steps.get(step.id)) && Object.hasOwn(graph.overlay || {}, step.id)) overlay[step.id] = graph.overlay[step.id];
        }
        let notice = graph ? "Execution states belong to the captured run. Keep editing; changes apply to the next run." : "";
        if (graph && !baseline) notice = "Editing the draft. This execution uses another snapshot; use Executed snapshot to inspect its graph.";
        else if (graph && signature(draft) !== signature(baseline)) notice = "Draft changed since this run. New or modified steps have no execution state; run again to test these changes.";
        return { value: JSON.stringify(draft), overlay: JSON.stringify(overlay), readOnly: false, mode: "draft", hasSnapshot: !!graph, notice };
      },
    };
  }
  if (typeof module !== "undefined" && module.exports) module.exports = { createGraphProjection };
  else root.ecCreateGraphProjection = createGraphProjection;
})(typeof window !== "undefined" ? window : globalThis);

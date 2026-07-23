import { defineConfig } from "vite";

// Bundles the workflow-graph web component (Lit + elkjs, both inlined) into a single
// self-contained ESM. The output is written into the engine module's classpath resources
// so Spring Boot serves it same-origin at /eventconductor/workflow-graph.js, where mateu's
// Element/import mechanism loads it on demand (see WorkflowDefinitionEditor).
export default defineConfig({
  build: {
    outDir: "../src/main/resources/META-INF/resources/eventconductor",
    emptyOutDir: false,
    target: "es2022",
    minify: true,
    lib: {
      entry: "src/eventconductor-workflow-graph.ts",
      formats: ["es"],
      fileName: () => "workflow-graph.js",
    },
    rollupOptions: {
      // elkjs is lazy-imported inside the component; inline it so the whole component
      // ships as one file with no sibling chunks to resolve.
      output: { inlineDynamicImports: true },
    },
  },
});

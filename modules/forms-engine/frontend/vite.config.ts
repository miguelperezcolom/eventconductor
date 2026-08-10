import { defineConfig } from "vite";

// Bundles the form-editor web component (Lit inlined) into a single self-contained ESM. The
// output is written into the forms-engine module's classpath resources so Spring Boot serves it
// same-origin at /eventconductor/form-editor.js, where mateu's Element/import mechanism loads it
// on demand (see FormEditor). Mirrors modules/workflow-engine/frontend/vite.config.ts, minus elkjs.
export default defineConfig({
  build: {
    outDir: "../src/main/resources/META-INF/resources/eventconductor",
    emptyOutDir: false,
    target: "es2022",
    minify: true,
    lib: {
      entry: "src/eventconductor-form-editor.ts",
      formats: ["es"],
      fileName: () => "form-editor.js",
    },
    rollupOptions: {
      // Ship the whole component as one file with no sibling chunks to resolve.
      output: { inlineDynamicImports: true },
    },
  },
});

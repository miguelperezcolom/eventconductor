// Copies the shared graph bundle and the workflow-definition + form JSON schemas into the
// extension, so it ships self-contained copies. Run automatically by `npm run compile`.
const fs = require("fs");
const path = require("path");

const root = path.resolve(__dirname, "..");
const repo = path.resolve(root, "..", "..");

const assets = [
  {
    from: path.join(repo, "modules/workflow-engine/src/main/resources/META-INF/resources/eventconductor/workflow-graph.js"),
    to: path.join(root, "media/workflow-graph.js"),
  },
  {
    from: path.join(repo, "modules/workflow-engine/src/main/resources/workflow-definition-schema.json"),
    to: path.join(root, "schema/ec.schema.json"),
  },
  {
    from: path.join(repo, "modules/forms-engine/src/main/resources/form-schema.json"),
    to: path.join(root, "schema/form-schema.json"),
  },
];

let ok = true;
for (const { from, to } of assets) {
  if (!fs.existsSync(from)) {
    console.error(`[sync-assets] MISSING source: ${from}`);
    ok = false;
    continue;
  }
  fs.mkdirSync(path.dirname(to), { recursive: true });
  fs.copyFileSync(from, to);
  const kb = (fs.statSync(to).size / 1024).toFixed(0);
  console.log(`[sync-assets] ${path.relative(root, to)} (${kb} kB)`);
}
if (!ok) process.exit(1);

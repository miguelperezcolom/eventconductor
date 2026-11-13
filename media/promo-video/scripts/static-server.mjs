// A tiny static server for the repository root (the editor harness loads ES modules, which do
// not load from file://). Usage: import { serve } from './static-server.mjs'; const s = await serve(port);
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { extname, resolve, dirname, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
const root = resolve(dirname(fileURLToPath(import.meta.url)), '../../..');
const types = { '.html': 'text/html', '.js': 'text/javascript', '.mjs': 'text/javascript', '.json': 'application/json',
  '.yaml': 'text/yaml', '.woff2': 'font/woff2', '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png' };
export function serve(port) {
  return new Promise((ok) => {
    const server = createServer(async (req, res) => {
      const p = resolve(root, '.' + decodeURIComponent(new URL(req.url, 'http://x').pathname));
      if (!p.startsWith(root + sep)) { res.writeHead(403).end(); return; }
      try { const body = await readFile(p); res.writeHead(200, { 'content-type': types[extname(p)] || 'application/octet-stream' }).end(body); }
      catch { res.writeHead(404).end(); }
    });
    server.listen(port, '127.0.0.1', () => ok(server));
  });
}

# EventConductor documentation site

The EventConductor docs site, built with [Astro Starlight](https://starlight.astro.build)
and deployed to Netlify (see `netlify.toml`, which pins the Node version — build settings
live in the Netlify UI).

## Content

Pages live under `src/content/docs/`, one route per file:

- `guides/` — introduction, quickstart, architecture, deployment modes, workflow/form/rule
  definitions, workers, starting a process, retries/timeouts/compensation, user tasks,
  analytics, demos, UI manual, MCP (overview, Claude Desktop, custom tools),
  ia-agent-service, event storming, comparison.
- `reference/` — configuration, Java API, Kafka topics, step types, statuses,
  Maven plugin, observability.

`public/` also holds the published AI artifacts: `llms.txt` (served at the site root)
and the `eventconductor-ai-compact.md` / `eventconductor-ai-full.md` reference files,
plus screenshots and videos.

## Commands

All commands are run from `doc/`:

| Command                   | Action                                           |
| :------------------------ | :----------------------------------------------- |
| `npm install`             | Installs dependencies                            |
| `npm run dev`             | Starts local dev server at `localhost:4321`      |
| `npm run build`           | Build your production site to `./dist/`          |
| `npm run preview`         | Preview your build locally, before deploying     |
| `npm run astro ...`       | Run CLI commands like `astro add`, `astro check` |
| `npm run astro -- --help` | Get help using the Astro CLI                     |

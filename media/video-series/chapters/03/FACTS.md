# Chapter 3 — facts checked (against v2.41.0; VS Code extension 0.2.4 from the Marketplace)

| Claim | Source |
|---|---|
| `*.ec.yaml` opens in the graph editor, with schema validation | `plugins/vscode-eventconductor/package.json` at tag `plugins-v0.2.4` (customEditors selector `*.ec.yaml`); `doc/.../guides/ide-plugins.md` 11–20 |
| Same graph component as the console | `ide-plugins.md` 6–9 |
| Palette drag, Shift+drag a link, panel edits `onIterationFailure` | `ide-plugins.md` 44–55; CHANGELOG 2.39.0 l. 70 (panel fields) |
| Every change is written to the file; text beside the graph | `ide-plugins.md` ("Graph ↔ text"); filmed |
| Double-click an inline PROCESS to edit its body; breadcrumb; Esc / Back | CHANGELOG 2.39.0 l. 69; `ide-plugins.md` 55–61 |
| `eventconductor:validate` uses the same JSON schema; catches dangling references; fails the build | `ide-plugins.md` 17–19; `examples/workshop-hotel-booking/pom.xml` (plugin comment); real output (`validate-broken`: "Step 'prepare' references unknown precondition step 'plan-redy'") |
| The workshop binds it to the build | `examples/workshop-hotel-booking/pom.xml` (execution `validate`) |
| Console: definitions read-only with the graph, a Versions tab, a play (simulation) button | filmed `/workflow/definitions/group-arrival`; `ide-plugins.md` 44–48 (token-flow simulation) |
| IntelliJ IDEA 2024.2+: split editor, same graph editor | `ide-plugins.md` 91–100 — **named only, not filmed** (Playwright cannot drive IntelliJ) |

Recorder: Playwright `_electron` on VS Code 1.145.3 (macOS arm64, checksum-checked download) with the extension
installed from the Marketplace (`setup.sh` with `VSCODE=1`); `VSCODE_EXT=dev` loads `plugins/vscode-eventconductor` instead.

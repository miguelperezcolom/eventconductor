package io.mateu.workflow.security.console;

/**
 * The page a browser gets when {@link ConsoleAccess} refuses it a console: a short, styled
 * explanation in the console's own palette (light or dark, following the browser), instead of a
 * bare error page. It is static — nothing from the request, the user or the application's state is
 * written into it — and says only what the reader can do: sign in, or, for whoever runs the
 * application, which switch opens the console. The status stays 403.
 */
final class ConsoleRefusalPage {

    static final String DOCS_URL = "https://eventconductor.io/guides/security-defaults/";

    static final String HTML = """
            <!doctype html>
            <html lang="en">
            <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <meta name="robots" content="noindex">
            <title>Access denied · EventConductor</title>
            <style>
              :root {
                color-scheme: light dark;
                --ec-canvas-bg: #f8fafc; --ec-surface: #ffffff; --ec-border: #e2e8f0;
                --ec-text: #1e293b; --ec-text-dim: #64748b; --ec-primary: #2563eb; --ec-danger: #dc2626;
                --ec-code-bg: #f1f5f9;
              }
              @media (prefers-color-scheme: dark) {
                :root {
                  --ec-canvas-bg: #16181a; --ec-surface: #1f2123; --ec-border: #3a3d42;
                  --ec-text: #e8e9ea; --ec-text-dim: #a8adb4; --ec-primary: #60a5fa; --ec-danger: #f87171;
                  --ec-code-bg: #2a2e34;
                }
              }
              * { box-sizing: border-box; }
              html, body { margin: 0; min-height: 100%; }
              body {
                background: var(--ec-canvas-bg); color: var(--ec-text);
                font: 15px/1.55 -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif;
                display: flex; align-items: center; justify-content: center; min-height: 100vh; padding: 24px 16px;
              }
              main {
                width: 100%; max-width: 560px; background: var(--ec-surface); border: 1px solid var(--ec-border);
                border-radius: 12px; padding: 32px; box-shadow: 0 1px 3px rgba(0, 0, 0, .06);
              }
              .brand { color: var(--ec-text-dim); font-size: 13px; font-weight: 600; letter-spacing: .02em; margin: 0 0 20px; }
              .status {
                display: inline-block; color: var(--ec-danger); border: 1px solid currentColor; border-radius: 999px;
                font-size: 12px; font-weight: 600; padding: 2px 10px; margin: 0 0 12px;
              }
              h1 { font-size: 22px; line-height: 1.3; margin: 0 0 12px; }
              h2 { font-size: 14px; margin: 24px 0 6px; }
              p { margin: 0 0 10px; }
              .dim { color: var(--ec-text-dim); }
              code {
                font: 12.5px/1.4 ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
                background: var(--ec-code-bg); border-radius: 4px; padding: 1px 5px; overflow-wrap: anywhere;
              }
              a { color: var(--ec-primary); }
            </style>
            </head>
            <body>
            <main>
              <p class="brand">EventConductor</p>
              <p class="status">403 Forbidden</p>
              <h1>You're not allowed to see this console</h1>
              <p>This EventConductor console refuses anonymous access.</p>
              <h2>To get in</h2>
              <p class="dim">The console opens to whoever this application's Spring Security lets in &mdash; it asks
              for no extra role of its own &mdash; but no sign-in is set up for it yet. Ask the people who run the
              application to add one, then sign in through it.</p>
              <h2>If you run this application</h2>
              <p class="dim">Protect it with Spring Security. On a laptop or an isolated network only, set
              <code>{{ALLOW_ANONYMOUS}}=true</code>; to remove the admin UI, set
              <code>{{UI_ENABLED}}=false</code>.</p>
              <p><a href="{{DOCS}}" rel="noopener noreferrer">Secure by default: what the engine refuses and why</a></p>
            </main>
            </body>
            </html>
            """
            .replace("{{ALLOW_ANONYMOUS}}", ConsoleAccess.ALLOW_ANONYMOUS)
            .replace("{{UI_ENABLED}}", ConsoleAccess.UI_ENABLED)
            .replace("{{DOCS}}", DOCS_URL);

    private ConsoleRefusalPage() {
    }
}

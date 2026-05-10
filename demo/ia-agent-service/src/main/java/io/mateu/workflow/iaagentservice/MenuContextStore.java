package io.mateu.workflow.iaagentservice;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Caches the UI menu context per browser session.
 *
 * The menu is sent by the frontend (typically on the first request or when it changes).
 * Subsequent prompts reuse the last cached menu so the LLM always knows which screens
 * are available in the UI.
 */
@Component
public class MenuContextStore {

    private static final Logger log = LoggerFactory.getLogger(MenuContextStore.class);

    private final Cache<String, List<ChatRequest.MenuEntry>> menus;

    public MenuContextStore() {
        this.menus = Caffeine.newBuilder()
                .maximumSize(1_000)
                .expireAfterAccess(30, TimeUnit.MINUTES)
                .build();
    }

    /**
     * Stores the menu for the given session. No-ops if {@code entries} is null or empty
     * (keeps the previous value so stale menus are not accidentally cleared).
     */
    public void update(String sessionId, List<ChatRequest.MenuEntry> entries) {
        if (entries == null || entries.isEmpty()) return;
        menus.put(sessionId, entries);
        log.debug("Session {}: menu updated with {} entries", sessionId, entries.size());
    }

    /**
     * Returns a system-prompt-ready string that lists all available UI screens and
     * explains to the LLM how to trigger navigation.
     * Returns an empty string if no menu has been cached for the session yet.
     */
    public String buildMenuSystemPrompt(String sessionId) {
        var entries = menus.getIfPresent(sessionId);
        if (entries == null || entries.isEmpty()) return "";

        var sb = new StringBuilder();
        sb.append("## Pantallas disponibles en la UI\n\n");
        sb.append("El usuario está interactuando con una interfaz que tiene las siguientes pantallas:\n\n");

        for (var entry : entries) {
            String label = entry.path() != null
                    ? String.join(" > ", entry.path())
                    : "(sin nombre)";
            sb.append("- **").append(label).append("**");
            if (entry.navigation() != null) {
                sb.append(" — ruta: `").append(entry.navigation().route()).append("`");
                if (entry.navigation().actionId() != null && !entry.navigation().actionId().isBlank()) {
                    sb.append(", acción: `").append(entry.navigation().actionId()).append("`");
                }
            }
            sb.append("\n");
        }

        sb.append("""

## Navegación desde el agente

Cuando quieras abrir una pantalla en la UI del usuario (por ejemplo, tras crear un registro \
con una herramienta MCP), incluye exactamente este bloque en tu respuesta, en una línea aparte:

[NAVIGATE:{"route":"<ruta>","consumedRoute":"","actionId":"<acción>","baseUrl":"<baseUrl>","serverSideType":"<serverSideType>","uriPrefix":""}]

El sistema interceptará ese bloque, lo eliminará del texto mostrado al usuario y navegará \
automáticamente a la pantalla indicada. Usa los valores exactos del campo `navigation` de \
la pantalla que quieras abrir.
""");

        return sb.toString();
    }
}

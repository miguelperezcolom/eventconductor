package io.mateu.workflow.iaagentservice;

import java.util.List;

/**
 * Request body for /api/agent/chat and /api/agent/stream.
 *
 * @param message     User's text input.
 * @param sessionId   Browser-side chat session identifier (used for conversation history
 *                    and menu context caching).
 * @param menuContext Full application menu flattened as a list of navigable screens.
 *                    Only needs to be sent when the menu changes; subsequent requests
 *                    may omit it and the last cached value will be used.
 */
public record ChatRequest(
        String message,
        String sessionId,
        List<MenuEntry> menuContext
) {
    public record MenuEntry(
            List<String> path,
            NavigationDetail navigation
    ) {}

    public record NavigationDetail(
            String route,
            String consumedRoute,
            String actionId,
            String baseUrl,
            String serverSideType,
            String uriPrefix
    ) {}
}

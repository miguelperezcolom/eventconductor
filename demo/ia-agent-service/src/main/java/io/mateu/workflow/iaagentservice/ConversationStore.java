package io.mateu.workflow.iaagentservice;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Cache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Stores the last N prompt/response exchanges and cumulative token usage per browser session.
 *
 * Backed by Caffeine so sessions expire automatically after inactivity and memory
 * is bounded.  Uses Cache.asMap().compute() for atomic updates — safe under
 * concurrent requests from the same session.
 */
@Component
public class ConversationStore {

    private static final Logger log = LoggerFactory.getLogger(ConversationStore.class);

    /** Number of past exchanges (user + assistant pairs) kept per session. */
    private static final int MAX_EXCHANGES = 5;

    private final Cache<String, List<Message>> sessions;

    /** Accumulated token counts per session: [inputTokens, outputTokens, totalTokens]. */
    private final Cache<String, int[]> tokenTotals;

    public ConversationStore() {
        this.sessions = Caffeine.newBuilder()
                .maximumSize(1_000)
                .expireAfterAccess(30, TimeUnit.MINUTES)
                .build();
        this.tokenTotals = Caffeine.newBuilder()
                .maximumSize(1_000)
                .expireAfterAccess(30, TimeUnit.MINUTES)
                .build();
    }

    /**
     * Returns the conversation history for the given session as an immutable list,
     * ready to be passed to {@code ChatClientRequestSpec.messages()}.
     * Returns an empty list for unknown sessions.
     */
    public List<Message> getHistory(String sessionId) {
        List<Message> history = sessions.getIfPresent(sessionId);
        return history != null ? List.copyOf(history) : List.of();
    }

    /**
     * Appends a user/assistant exchange to the session history and trims the list
     * so at most {@value MAX_EXCHANGES} exchanges are retained.
     */
    public void accumulateTokens(String sessionId, int input, int output, int total) {
        tokenTotals.asMap().merge(sessionId, new int[]{input, output, total},
                (existing, delta) -> new int[]{
                        existing[0] + delta[0],
                        existing[1] + delta[1],
                        existing[2] + delta[2]});
    }

    public int[] getTotalTokens(String sessionId) {
        int[] totals = tokenTotals.getIfPresent(sessionId);
        return totals != null ? totals : new int[]{0, 0, 0};
    }

    public void addExchange(String sessionId, String userText, String assistantText) {
        sessions.asMap().compute(sessionId, (id, existing) -> {
            List<Message> history = existing != null ? new ArrayList<>(existing) : new ArrayList<>();
            history.add(new UserMessage(userText));
            history.add(new AssistantMessage(assistantText));
            int max = MAX_EXCHANGES * 2;
            if (history.size() > max) {
                history = new ArrayList<>(history.subList(history.size() - max, history.size()));
            }
            return history;
        });
        log.debug("Session {}: {} messages in history", sessionId,
                sessions.getIfPresent(sessionId) != null ? sessions.getIfPresent(sessionId).size() : 0);
    }
}

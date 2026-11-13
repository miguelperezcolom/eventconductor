package com.example.hotel.fakes;

import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * WORKSHOP ONLY — a stand-in for a language model, speaking the OpenAI chat-completions API, so
 * module 5 runs without a key and sends nothing anywhere. It is not clever: a request that asks for
 * JSON gets a fixed classification of the guest's special request, anything else a short
 * confirmation email. Point {@code workflow.llm.connections.assistant} at a real provider to use a
 * real model — the definitions do not change.
 */
@RestController
@RequestMapping("/fake/llm")
public class FakeLanguageModel {

    @PostMapping({"/chat/completions", "/v1/chat/completions"})
    public Map<String, Object> complete(@RequestBody Map<String, Object> request) {
        var prompt = String.valueOf(request.get("messages"));
        String answer;
        if (prompt.contains("JSON")) {
            var lower = prompt.toLowerCase();
            var category = lower.contains("wheelchair") || lower.contains("accessib") ? "accessibility"
                    : lower.contains("cot") || lower.contains("baby") ? "family"
                    : lower.contains("late") ? "arrival"
                    : "other";
            answer = "{\"category\": \"" + category + "\", \"needsStaff\": " + !"other".equals(category)
                    + ", \"summary\": \"The guest asked for something the front desk should prepare.\"}";
        } else {
            answer = "Dear guest,\n\nYour booking is confirmed. We look forward to welcoming you.\n\n"
                    + "Kind regards,\nThe Workshop Hotel";
        }
        return Map.of(
                "id", "chatcmpl-workshop",
                "object", "chat.completion",
                "created", Instant.now().getEpochSecond(),
                "model", String.valueOf(request.getOrDefault("model", "fake-model")),
                "choices", List.of(Map.of(
                        "index", 0,
                        "message", Map.of("role", "assistant", "content", answer),
                        "finish_reason", "stop")),
                "usage", Map.of("prompt_tokens", 120, "completion_tokens", 40, "total_tokens", 160));
    }
}

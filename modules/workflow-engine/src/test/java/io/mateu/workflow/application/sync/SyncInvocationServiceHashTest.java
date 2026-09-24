package io.mateu.workflow.application.sync;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SyncInvocationServiceHashTest {

    private static SyncInvocationService.StartRequest request(String businessKey, Map<String, String> variables,
                                                              Duration wait) {
        return new SyncInvocationService.StartRequest("wd", "k", businessKey, variables, wait, null);
    }

    @Test
    void theHashIgnoresVariableOrderAndTheWait() {
        var ab = new LinkedHashMap<String, String>();
        ab.put("a", "1");
        ab.put("b", "2");
        var ba = new LinkedHashMap<String, String>();
        ba.put("b", "2");
        ba.put("a", "1");
        assertThat(SyncInvocationService.hashOf(request("bk", ab, Duration.ofSeconds(1))))
                .isEqualTo(SyncInvocationService.hashOf(request("bk", ba, null)));
    }

    @Test
    void theHashSeesTheBusinessKeyAndTheValues() {
        var base = SyncInvocationService.hashOf(request("bk", Map.of("a", "1"), null));
        assertThat(SyncInvocationService.hashOf(request("other", Map.of("a", "1"), null))).isNotEqualTo(base);
        assertThat(SyncInvocationService.hashOf(request("bk", Map.of("a", "2"), null))).isNotEqualTo(base);
    }
}

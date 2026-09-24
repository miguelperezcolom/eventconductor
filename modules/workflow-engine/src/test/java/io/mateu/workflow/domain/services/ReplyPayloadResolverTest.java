package io.mateu.workflow.domain.services;

import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.Step;
import io.mateu.workflow.domain.aggregates.StepType;
import io.mateu.workflow.domain.aggregates.Variable;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReplyPayloadResolverTest {

    private static final Step REPLY = new Step("reply", "wd", StepType.REPLY, "Reply", null, "start", null,
            null, false, null, null, null, null, null, 0, null, null, null, null, 0, 0, false, null, 0, null);

    private static Process process() {
        return Process.builder().id("p-1").businessKey("BK-9")
                .variables(List.of(new Variable("bookingId", "B-1"), new Variable("amount", "120")))
                .build();
    }

    @Test
    void variables_become_an_object_with_missing_ones_as_null() {
        var result = ReplyPayloadResolver.resolve(REPLY.withReplyVariables(List.of("bookingId", "missing")),
                process(), 0);
        assertThat(result.error()).isNull();
        assertThat(result.json()).isEqualTo("{\"bookingId\":\"B-1\",\"missing\":null}");
    }

    @Test
    void an_expression_can_build_a_map_and_see_the_business_key() {
        var result = ReplyPayloadResolver.resolve(
                REPLY.withReplyExpression("{'id': bookingId, 'key': businessKey, 'ok': true}"), process(), 0);
        assertThat(result.error()).isNull();
        assertThat(result.json()).contains("\"id\":\"B-1\"").contains("\"key\":\"BK-9\"").contains("\"ok\":true");
    }

    @Test
    void a_scalar_expression_is_json_too() {
        assertThat(ReplyPayloadResolver.resolve(REPLY.withReplyExpression("bookingId"), process(), 0).json())
                .isEqualTo("\"B-1\"");
        assertThat(ReplyPayloadResolver.resolve(REPLY.withReplyExpression("nothingHere"), process(), 0).json())
                .isEqualTo("null");
    }

    @Test
    void no_source_is_an_empty_object() {
        assertThat(ReplyPayloadResolver.resolve(REPLY, process(), 0).json()).isEqualTo("{}");
    }

    @Test
    void both_sources_fail() {
        var result = ReplyPayloadResolver.resolve(
                REPLY.withReplyVariables(List.of("a")).withReplyExpression("b"), process(), 0);
        assertThat(result.json()).isNull();
        assertThat(result.error()).contains("both");
    }

    @Test
    void an_expression_that_does_not_parse_fails() {
        var result = ReplyPayloadResolver.resolve(REPLY.withReplyExpression("{{{"), process(), 0);
        assertThat(result.error()).contains("could not be evaluated");
    }

    @Test
    void a_reply_over_the_limit_fails() {
        var result = ReplyPayloadResolver.resolve(REPLY.withReplyVariables(List.of("bookingId")), process(), 5);
        assertThat(result.error()).contains("over the 5-byte limit");
    }
}

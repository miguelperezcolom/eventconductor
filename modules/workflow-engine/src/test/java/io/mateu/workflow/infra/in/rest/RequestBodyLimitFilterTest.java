package io.mateu.workflow.infra.in.rest;

import io.mateu.workflow.application.services.MessageDispatcher;
import io.mateu.workflow.dtos.events.integration.MessageReceived;
import io.mateu.workflow.infra.config.MessageApiProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HARD-BODY-01..03 — the byte ceiling on the engine's HTTP surface: a body big enough that reading it is itself the
 * attack never gets read.
 *
 * <p>Driven through MockMvc with the filter in the chain rather than by calling the filter, because
 * what is being asserted is that the request dies <em>before</em> the controller — a 413 with the
 * dispatcher untouched, not a controller that ran and then said no.
 */
@ExtendWith(MockitoExtension.class)
class RequestBodyLimitFilterTest {

    @Mock MessageDispatcher messageDispatcher;

    private static final int LIMIT = 1_000;

    private MockMvc limitedTo(int maxBytes) {
        var properties = new MessageApiProperties();
        return MockMvcBuilders
                .standaloneSetup(new MessageRestController(properties, messageDispatcher))
                .addFilters(new RequestBodyLimitFilter(maxBytes))
                .build();
    }

    private static String bodyOf(int variableLength) {
        return "{ \"messageName\": \"m\", \"correlationKey\": \"k\", \"variables\": { \"payload\": \""
                + "x".repeat(variableLength) + "\" } }";
    }

    @Test
    void aBodyOverTheLimitIsRefusedAndNeverReachesTheController() throws Exception {
        limitedTo(LIMIT).perform(post("/workflow/api/messages")
                        .contentType("application/json").content(bodyOf(LIMIT * 10)))
                .andExpect(status().isPayloadTooLarge());

        verify(messageDispatcher, never()).dispatch(any());
    }

    /**
     * The same body with no {@code Content-Length} to go on. A chunked request declares nothing, so
     * the fast path cannot fire and the counting stream is the whole defence — this is the test that
     * fails if the stream wrapper stops being used. Driven at the filter rather than through MockMvc
     * because {@code MockHttpServletRequest} derives its content length from the bytes it holds and
     * so can never present the case being tested; the length is overridden to the {@code -1} a
     * container reports for a chunked request.
     */
    @Test
    void aBodyThatDeclaresNoLengthIsStillCutOffWhileItIsRead() throws Exception {
        var request = new MockHttpServletRequest("POST", "/workflow/api/messages") {
            @Override
            public long getContentLengthLong() {
                return -1;
            }

            @Override
            public int getContentLength() {
                return -1;
            }
        };
        request.setContentType("application/json");
        request.setContent(bodyOf(LIMIT * 10).getBytes(StandardCharsets.UTF_8));
        var response = new MockHttpServletResponse();
        // What any body reader does, and the only thing this needs to stand in for.
        FilterChain readTheBody = (req, res) -> ((HttpServletRequest) req).getInputStream().readAllBytes();

        new RequestBodyLimitFilter(LIMIT).doFilter(request, response, readTheBody);

        assertThat(response.getStatus()).isEqualTo(413);
    }

    @Test
    void aBodyUnderTheLimitPassesThroughUntouched() throws Exception {
        limitedTo(LIMIT).perform(post("/workflow/api/messages")
                        .contentType("application/json").content(bodyOf(10)))
                .andExpect(status().isAccepted());

        verify(messageDispatcher).dispatch(any(MessageReceived.class));
    }
}

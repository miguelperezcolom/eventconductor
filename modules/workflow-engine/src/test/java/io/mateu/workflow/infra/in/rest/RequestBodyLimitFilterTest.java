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
        properties.setAllowAnonymous(true);
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

    private static MockHttpServletRequest chunked(int bytes) {
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
        request.setContent(bodyOf(bytes).getBytes(StandardCharsets.UTF_8));
        return request;
    }

    /** Read a byte at a time, or through the reader a form parser uses: still counted, still cut off. */
    @Test
    void everyReadPathIsCounted() throws Exception {
        FilterChain byteAtATime = (req, res) -> {
            var in = ((HttpServletRequest) req).getInputStream();
            while (in.read() != -1) {
                // read on
            }
        };
        var response = new MockHttpServletResponse();
        new RequestBodyLimitFilter(LIMIT).doFilter(chunked(LIMIT * 10), response, byteAtATime);
        assertThat(response.getStatus()).isEqualTo(413);

        FilterChain throughTheReader = (req, res) -> {
            var reader = ((HttpServletRequest) req).getReader();
            assertThat(((HttpServletRequest) req).getReader()).isSameAs(reader);
            while (reader.read() != -1) {
                // read on
            }
        };
        response = new MockHttpServletResponse();
        new RequestBodyLimitFilter(LIMIT).doFilter(chunked(LIMIT * 10), response, throughTheReader);
        assertThat(response.getStatus()).isEqualTo(413);
    }

    /** The reader decodes with the request's charset, UTF-8 when it names none. */
    @Test
    void aBodyUnderTheLimitReadsTheSameThroughEveryPath() throws Exception {
        var seen = new StringBuilder();
        FilterChain chain = (req, res) -> {
            var in = ((HttpServletRequest) req).getInputStream();
            assertThat(in.isFinished()).isFalse();
            assertThat(in.isReady()).isTrue();
            assertThat(in.available()).isPositive();
            assertThat(((HttpServletRequest) req).getInputStream()).isSameAs(in);
            seen.append(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            assertThat(in.isFinished()).isTrue();
            in.close();
        };
        var request = chunked(10);
        var response = new MockHttpServletResponse();
        new RequestBodyLimitFilter(LIMIT).doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(seen.toString()).isEqualTo(bodyOf(10));

        var latin = chunked(0);
        latin.setCharacterEncoding("ISO-8859-1");
        latin.setContent(new byte[]{(byte) 0xE9});
        var text = new StringBuilder();
        new RequestBodyLimitFilter(LIMIT).doFilter(latin, new MockHttpServletResponse(),
                (req, res) -> text.append(((HttpServletRequest) req).getReader().readLine()));
        assertThat(text.toString()).isEqualTo("\u00e9");
    }

    /** Whoever read the body may wrap the refusal: the cause chain is walked, the 413 still answered. */
    @Test
    void aWrappedRefusalIsStillA413_andAnyOtherFailurePropagates() throws Exception {
        FilterChain wrapping = (req, res) -> {
            try {
                ((HttpServletRequest) req).getInputStream().readAllBytes();
            } catch (java.io.IOException e) {
                throw new IllegalStateException("could not parse", new RuntimeException("reading", e));
            }
        };
        var response = new MockHttpServletResponse();
        new RequestBodyLimitFilter(LIMIT).doFilter(chunked(LIMIT * 10), response, wrapping);
        assertThat(response.getStatus()).isEqualTo(413);

        FilterChain failing = (req, res) -> {
            throw new IllegalStateException("unrelated");
        };
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        new RequestBodyLimitFilter(LIMIT).doFilter(chunked(10), new MockHttpServletResponse(), failing))
                .isInstanceOf(IllegalStateException.class).hasMessage("unrelated");
    }

    /** Registered for the engine's own doors only, ahead of anything that reads the body. */
    @Test
    void theFilterIsRegisteredOnTheEnginesPathsOnly() {
        var registration = new RequestBodyLimitConfiguration().requestBodyLimitFilter(1024);

        assertThat(registration.getUrlPatterns()).containsExactlyInAnyOrder(
                "/workflow/api/*", "/workflow/webhooks/*", "/forms/webhooks/*", "/rules/webhooks/*");
        assertThat(registration.getOrder()).isEqualTo(org.springframework.core.Ordered.HIGHEST_PRECEDENCE + 100);
        assertThat(registration.getFilterName()).isEqualTo("workflowRequestBodyLimitFilter");
        assertThat(registration.getFilter()).isInstanceOf(RequestBodyLimitFilter.class);
    }
}

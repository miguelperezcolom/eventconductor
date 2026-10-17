package io.mateu.workflow.webhook;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** A webhook body over workflow.webhooks.max-body-bytes is refused with 413 before it reaches the controller. */
class WebhookBodyLimitFilterTest {

    final List<String> published = new ArrayList<>();
    WebhookGatewayProperties properties;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        properties = new WebhookGatewayProperties();
        properties.setMaxBodyBytes(64);
        var partner = new WebhookGatewayProperties.Endpoint();
        partner.setProvider(WebhookGatewayProperties.Provider.GENERIC);
        partner.setDestination("partner-events");
        partner.setType("com.partner.changed");
        properties.getEndpoints().put("partner", partner);
        var controller = new WebhookController(properties, new WebhookGatewayAutoConfiguration().webhookProviders(),
                (destination, event) -> published.add(event.data()), null);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .addFilters(new WebhookGatewayAutoConfiguration().webhookBodyLimitFilter(properties)).build();
    }

    @Test
    void aBodyWithinTheLimitPassesUnchanged() throws Exception {
        mvc.perform(post("/webhooks/partner").contentType("application/json").content("{\"a\":1}"))
                .andExpect(status().isAccepted());
        assertThat(published).containsExactly("{\"a\":1}");
    }

    @Test
    void aDeclaredOversizedBodyIs413AndNothingIsPublished() throws Exception {
        mvc.perform(post("/webhooks/partner").contentType("application/json").content("{\"a\":\"" + "x".repeat(100) + "\"}"))
                .andExpect(status().isPayloadTooLarge());
        assertThat(published).isEmpty();
    }

    @Test
    void anUndeclaredOversizedBodyIsCutOffAt413() throws Exception {
        var request = new MockHttpServletRequest("POST", "/webhooks/partner") {
            @Override
            public long getContentLengthLong() {
                return -1; // chunked: no Content-Length to go by
            }
        };
        request.setContent("y".repeat(1000).getBytes(StandardCharsets.UTF_8));
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();
        new WebhookBodyLimitFilter(properties).doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(chain.getRequest()).as("the controller is never reached").isNull();
    }

    @Test
    void otherPathsAreNotFiltered() throws Exception {
        var request = new MockHttpServletRequest("POST", "/upload");
        request.setContent(new byte[1000]);
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();
        new WebhookBodyLimitFilter(properties).doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isSameAs(request);
    }

    @Test
    void theBodyIsHandedOnReadableAsStreamOrReaderUnderAContextPath() throws Exception {
        var request = new MockHttpServletRequest("POST", "/app/webhooks/partner");
        request.setContextPath("/app");
        request.setCharacterEncoding("ISO-8859-1");
        request.setContent("caf\u00e9".getBytes(StandardCharsets.ISO_8859_1));
        var seen = new ArrayList<String>();
        jakarta.servlet.FilterChain chain = (req, res) -> {
            var wrapped = (jakarta.servlet.http.HttpServletRequest) req;
            seen.add(String.valueOf(wrapped.getContentLength()));
            seen.add(String.valueOf(wrapped.getContentLengthLong()));
            seen.add(wrapped.getReader().readLine());
            var in = wrapped.getInputStream();
            assertThat(in.isReady()).isTrue();
            assertThat(in.isFinished()).isFalse();
            seen.add(new String(in.readAllBytes(), StandardCharsets.ISO_8859_1));
            assertThat(in.isFinished()).isTrue();
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> in.setReadListener(null))
                    .isInstanceOf(UnsupportedOperationException.class);
        };

        new WebhookBodyLimitFilter(properties).doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(seen).containsExactly("4", "4", "caf\u00e9", "caf\u00e9");
    }

    @Test
    void aBodyWithoutDeclaredEncodingIsReadAsUtf8AndACustomPathIsFiltered() throws Exception {
        properties.setPath("/hooks/");
        var request = new MockHttpServletRequest("POST", "/hooks/partner");
        request.setContent("\u00f1".getBytes(StandardCharsets.UTF_8));
        var seen = new ArrayList<String>();
        jakarta.servlet.FilterChain chain = (req, res) -> seen.add(req.getReader().readLine());

        new WebhookBodyLimitFilter(properties).doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(seen).containsExactly("\u00f1");
        // The default prefix no longer applies.
        var other = new MockHttpServletRequest("POST", "/webhooks/partner");
        other.setContent(new byte[1000]);
        var passed = new MockFilterChain();
        new WebhookBodyLimitFilter(properties).doFilter(other, new MockHttpServletResponse(), passed);
        assertThat(passed.getRequest()).isSameAs(other);
    }

    @Test
    void runsBeforeTheRestOfTheChain() {
        assertThat(new WebhookBodyLimitFilter(properties).getOrder())
                .isLessThan(org.springframework.core.Ordered.LOWEST_PRECEDENCE)
                .isEqualTo(org.springframework.core.Ordered.HIGHEST_PRECEDENCE + 100);
    }
}

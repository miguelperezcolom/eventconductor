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
}

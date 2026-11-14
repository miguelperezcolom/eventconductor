package io.mateu.workflow.infra.in.ui;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/** A console page asked for by its kebab-case name lands on the page; nothing else is touched. */
class ConsoleRouteAliasesTest {

    private final ConsoleRouteAliases filter = new ConsoleRouteAliases();

    private MockHttpServletResponse get(String path, String accept) throws Exception {
        var request = new MockHttpServletRequest("GET", path);
        if (accept != null) {
            request.addHeader("Accept", accept);
        }
        request.setQueryString(path.contains("?") ? null : "status=ERROR");
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    @Test
    void aPageByItsKebabCaseNameIsRedirectedToTheRoute() throws Exception {
        var response = get("/workflow/batch-operations", "text/html,application/xhtml+xml");

        assertThat(response.getStatus()).isEqualTo(301);
        assertThat(response.getRedirectedUrl()).isEqualTo("/workflow/batchOperations?status=ERROR");
        assertThat(ConsoleRouteAliases.aliased("/workflow/event-triggers/t-1")).isEqualTo("/workflow/eventTriggers/t-1");
        assertThat(ConsoleRouteAliases.aliased("/_workflow/workflow/received-events")).isEqualTo("/_workflow/workflow/receivedEvents");
    }

    @Test
    void theApiAndEverythingThatIsNotAPageAreLeftAlone() throws Exception {
        assertThat(get("/workflow/api/batch-operations", "text/html").getStatus()).isEqualTo(200);
        assertThat(get("/workflow/batch-operations", "application/json").getStatus()).isEqualTo(200);
        assertThat(get("/workflow/batch-operations", null).getStatus()).isEqualTo(200);
        assertThat(get("/mateu/v3/sync/workflow/batch-operations", "text/html").getStatus()).isEqualTo(200);
        assertThat(get("/workflow/batchOperations", "text/html").getStatus()).isEqualTo(200);
        assertThat(ConsoleRouteAliases.aliased("/workflow/processes")).isNull();
        assertThat(ConsoleRouteAliases.aliased("")).isNull();

        var post = new MockHttpServletRequest("POST", "/workflow/batch-operations");
        post.addHeader("Accept", "text/html");
        var response = new MockHttpServletResponse();
        filter.doFilter(post, response, new MockFilterChain());
        assertThat(response.getStatus()).isEqualTo(200);
    }
}

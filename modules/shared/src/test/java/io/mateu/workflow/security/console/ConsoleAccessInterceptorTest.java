package io.mateu.workflow.security.console;

import io.mateu.mcp.FakeMcpHandler;
import io.mateu.workflow.infra.in.rest.RuleReadController;
import io.mateu.workflow.infra.in.ui.FakeConsoleController;
import io.mateu.workflow.security.console.app.AppApiController;
import io.mateu.workflow.security.console.app.AppHomeController;
import io.mateu.workflow.security.console.app.AppHomeMateuController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.net.URI;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * The consoles in an application without Spring Security: refused unless allow-anonymous, let in
 * with a filter chain, judged on the handler a request reaches whatever its spelling — and nothing
 * else touched.
 */
class ConsoleAccessInterceptorTest {

    /** Stands in for SecurityFilterChain, which is not on this classpath. */
    interface FakeFilterChain {
    }

    private static ConsoleAccess access(boolean chain, Boolean allowAnonymous) {
        var beans = new StaticListableBeanFactory();
        beans.addBean("console", new FakeConsoleController());
        return access(beans, chain, allowAnonymous);
    }

    private static ConsoleAccess access(StaticListableBeanFactory beans, boolean chain, Boolean allowAnonymous) {
        if (chain) {
            beans.addBean("chain", new FakeFilterChain() { });
        }
        var environment = new MockEnvironment();
        if (allowAnonymous != null) {
            environment.setProperty(ConsoleAccess.ALLOW_ANONYMOUS, allowAnonymous.toString());
        }
        var access = new ConsoleAccess(beans, environment, FakeFilterChain.class.getName());
        access.afterSingletonsInstantiated();
        return access;
    }

    private static MockMvc mvc(ConsoleAccess access) {
        return MockMvcBuilders.standaloneSetup(new FakeConsoleController(), new RuleReadController(),
                        new AppHomeController(), new AppHomeMateuController(), new AppApiController())
                .addInterceptors(new ConsoleAccessInterceptor(access))
                .build();
    }

    private static final List<RequestBuilder> CONSOLES = List.of(
            get("/_workflow"),
            post("/_workflow/mateu/v3/sse/processes").content("{}"),
            get("/eventconductor/tasks/billing/module.zip"),
            get("/rules"),
            get("/rules/r1"),
            get("/"),
            post("/mateu/v3/workflow/processes").content("{}"));

    private static MvcResult perform(MockMvc mvc, RequestBuilder request) throws Exception {
        return mvc.perform(request).andReturn();
    }

    @Test
    void withoutAFilterChainTheConsolesAreRefused() throws Exception {
        for (var allowAnonymous : new Boolean[] {null, false}) {
            var mvc = mvc(access(false, allowAnonymous));
            for (var request : CONSOLES) {
                var result = perform(mvc, request);
                assertThat(result.getResponse().getStatus()).as(result.getRequest().getRequestURI()).isEqualTo(403);
                assertThat(result.getResponse().getContentAsString())
                        .contains("Spring Security", ConsoleAccess.ALLOW_ANONYMOUS + "=true",
                                ConsoleAccess.UI_ENABLED + "=false")
                        .doesNotContain("console-", "rules\"", "app-");
            }
        }
    }

    @Test
    void aBrowserGetsAPageAndAnythingElseJson() throws Exception {
        var mvc = mvc(access(false, false));
        var page = perform(mvc, get("/_workflow").header("Accept", "text/html,application/xhtml+xml"));
        assertThat(page.getResponse().getContentType()).startsWith("text/html");
        assertThat(page.getResponse().getContentAsString()).contains("<h1>403 Forbidden</h1>", "Spring Security");

        var api = perform(mvc, get("/rules").header("Accept", "application/json"));
        assertThat(api.getResponse().getContentType()).startsWith("application/json");
        assertThat(api.getResponse().getContentAsString()).startsWith("{\"status\":403");
    }

    @Test
    void allowAnonymousOrAFilterChainLetsThemIn() throws Exception {
        for (var access : List.of(access(false, true), access(true, null), access(true, false))) {
            var mvc = mvc(access);
            for (var request : CONSOLES) {
                var result = perform(mvc, request);
                assertThat(result.getResponse().getStatus()).as(result.getRequest().getRequestURI()).isEqualTo(200);
            }
        }
    }

    @Test
    void theApplicationsOwnEndpointsAndTheHealthProbeAreUntouched() throws Exception {
        var mvc = mvc(access(false, false));
        for (var path : List.of("/api/hello", "/actuator/health", "/OrderController")) {
            assertThat(perform(mvc, get(path)).getResponse().getStatus()).as(path).isEqualTo(200);
        }
    }

    /** Whatever spelling reaches a console's handler is judged as that console: the decision is on the handler. */
    @Test
    void noSpellingOfAConsolePathGetsThrough() throws Exception {
        var mvc = mvc(access(false, false));
        for (var uri : List.of("/_workflow;jsessionid=abc", "/%5Fworkflow", "/_workflow/", "//_workflow",
                "/rules;x=y", "/rules/", "/r%75les", "/rules/r1;a=b", "/rules/%2E%2E/rules", "/./rules",
                "/eventconductor/tasks/billing/module.zip;x=y", "/eventconductor/tasks/billing/module%2Ezip",
                "/mateu/v3/;x=y/workflow")) {
            var result = perform(mvc, get(URI.create(uri)));
            var postResult = perform(mvc, post(URI.create(uri)).content("{}"));
            for (var r : List.of(result, postResult)) {
                assertThat(r.getResponse().getStatus()).as(uri).isIn(403, 404, 405);
                assertThat(r.getResponse().getContentAsString()).as(uri)
                        .doesNotContain("console-", "app-").isNotIn("rules", "rule");
            }
        }
        assertThat(perform(mvc, get(URI.create("/%5Fworkflow"))).getResponse().getStatus())
                .as("decoded by Spring and refused, not served").isEqualTo(403);
        assertThat(perform(mvc, get(URI.create("/_workflow;jsessionid=abc"))).getResponse().getStatus())
                .isEqualTo(403);
    }

    /** Stands in for an MCP SDK / Spring AI transport handler. */
    static final Object SDK_MCP_HANDLER = new io.modelcontextprotocol.FakeSdkHandler();

    @Test
    void mcpHandlersAndMateuInfrastructureAreConsolesWhereTheEngineIs() {
        var withConsoles = new ConsoleAccessInterceptor(access(false, false));
        assertThat(withConsoles.isGuarded(new FakeMcpHandler())).as("Mateu's MCP drives the consoles").isTrue();
        assertThat(withConsoles.isGuarded(SDK_MCP_HANDLER)).isTrue();
        assertThat(withConsoles.isGuarded(new Object())).isFalse();
        assertThat(withConsoles.isGuarded(null)).isFalse();
        assertThat(ConsoleAccessInterceptor.isMateuUi(AppHomeMateuController.class)).isTrue();
        assertThat(ConsoleAccessInterceptor.isMateuUi(AppHomeController.class)).isTrue();
        assertThat(ConsoleAccessInterceptor.isMateuUi(AppApiController.class)).isFalse();

        // the engine's tools without its UI (workflow.ui.enabled=false): an MCP server exposing them is guarded
        var toolsOnly = new StaticListableBeanFactory();
        toolsOnly.addBean("tools", new io.mateu.workflow.mcp.McpTools() { });
        var tools = new ConsoleAccessInterceptor(access(toolsOnly, false, false));
        assertThat(tools.isGuarded(SDK_MCP_HANDLER)).isTrue();
        assertThat(tools.isGuarded(new FakeMcpHandler())).as("no console for Mateu's MCP to drive").isFalse();
    }

    /** A worker with Mateu pages of its own, and no console: its UIs and MCP are none of this rule's business. */
    @Test
    void anApplicationWithoutAConsoleKeepsItsOwnUis() throws Exception {
        var access = access(new StaticListableBeanFactory(), false, false);
        assertThat(access.consolesPresent()).isFalse();
        assertThat(access.engineToolsPresent()).isFalse();
        var mvc = mvc(access);
        assertThat(perform(mvc, get("/")).getResponse().getStatus()).isEqualTo(200);
        assertThat(perform(mvc, post("/mateu/v3/home").content("{}")).getResponse().getStatus()).isEqualTo(200);
        assertThat(new ConsoleAccessInterceptor(access).isGuarded(SDK_MCP_HANDLER)).isFalse();
        assertThat(new ConsoleAccessInterceptor(access).isGuarded(new FakeMcpHandler())).isFalse();
        assertThat(perform(mvc, get("/rules")).getResponse().getStatus())
                .as("the rule-read endpoints are always guarded").isEqualTo(403);
    }

    @Test
    void aRefusedMcpCallIsAnswered403() throws Exception {
        var response = new org.springframework.mock.web.MockHttpServletResponse();
        var interceptor = new ConsoleAccessInterceptor(access(false, false));
        assertThat(interceptor.preHandle(new org.springframework.mock.web.MockHttpServletRequest("POST", "/mateu/mcp"),
                response, new FakeMcpHandler())).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(new ConsoleAccessInterceptor(access(false, true)).preHandle(
                new org.springframework.mock.web.MockHttpServletRequest("POST", "/mateu/mcp"),
                new org.springframework.mock.web.MockHttpServletResponse(), new FakeMcpHandler())).isTrue();
    }

    @Test
    void theStatusSaysWhatIsProtectedAndHow() {
        assertThat(ConsoleAccess.status(true, false)).startsWith("Protected by the application's Spring Security");
        assertThat(ConsoleAccess.status(false, true)).startsWith("OPEN").contains(ConsoleAccess.ALLOW_ANONYMOUS);
        assertThat(ConsoleAccess.status(false, false)).startsWith("Refused (403)")
                .contains("Spring Security", ConsoleAccess.UI_ENABLED + "=false", "MCP", "GET /rules");
        assertThat(access(true, null).isSecuredByFilterChain()).isTrue();
        assertThat(ConsoleAccess.hasFilterChain(new StaticListableBeanFactory(), ConsoleAccess.SECURITY_FILTER_CHAIN))
                .as("Spring Security not on the classpath").isFalse();
    }
}

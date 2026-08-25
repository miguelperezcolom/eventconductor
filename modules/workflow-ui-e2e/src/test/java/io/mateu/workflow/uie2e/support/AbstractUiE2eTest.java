package io.mateu.workflow.uie2e.support;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.assertions.PlaywrightAssertions;
import io.mateu.workflow.dtos.Variable;
import io.mateu.workflow.dtos.events.integration.ProcessCreationRequested;
import io.mateu.workflow.infra.in.async.processupstreamevent.ProcessUpstreamEventCommand;
import io.mateu.workflow.infra.in.async.processupstreamevent.ProcessUpstreamEventUseCase;
import io.mateu.workflow.uie2e.pages.WorkflowUi;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.util.List;

/**
 * Boots the engine with its UI on a random port and drives it with a real browser.
 *
 * <p><b>Why a browser at all.</b> Everything below this is already covered by the unit and e2e
 * suites, which drive the engine through its ports. What they cannot see is the half an operator
 * actually touches: whether the grid renders the process, whether the status badge says what the
 * database says, and whether the button labelled "Pause process" reaches the engine. The UI is
 * also the one place where a change to the framework underneath — Mateu, Vaadin — can break
 * everything while every Java test stays green.
 *
 * <p><b>Playwright rather than Selenium</b>, because the UI is nested open shadow roots four deep
 * ({@code mateu-ui} → {@code mateu-ux} → {@code mateu-app} → {@code vaadin-*}). Playwright's
 * selector engines pierce open shadow roots; with WebDriver every step would mean walking
 * {@code shadowRoot} by hand.
 *
 * <p><b>Assertions wait, they do not sample.</b> Operator actions are published as events and
 * carried out by whichever pod owns the process — the click does not perform them — and the detail
 * view polls every two seconds to catch up. So a test asserts that the badge <em>becomes</em>
 * COMPLETED, never that it is COMPLETED now. Playwright's web-first assertions retry until the
 * timeout, which is what makes that safe rather than a sleep.
 */
@SpringBootTest(classes = UiE2eApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class AbstractUiE2eTest {

    /**
     * Generous, because these wait on the engine's own asynchrony — the outbox relay, then the
     * view's two-second poll — and not on the browser. A tighter value would not find bugs, only
     * flakes.
     */
    private static final double ASSERTION_TIMEOUT_MS = 20_000;

    private static Playwright playwright;
    private static Browser browser;

    @LocalServerPort
    protected int port;

    @Autowired
    protected ProcessUpstreamEventUseCase processUpstreamEvents;

    @Autowired
    protected io.mateu.workflow.application.out.ProcessRepository processes;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    protected Page page;
    protected WorkflowUi ui;

    @BeforeAll
    static void startBrowser() {
        playwright = Playwright.create();
        browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
        PlaywrightAssertions.setDefaultAssertionTimeout(ASSERTION_TIMEOUT_MS);
    }

    @AfterAll
    static void stopBrowser() {
        if (browser != null) {
            browser.close();
        }
        if (playwright != null) {
            playwright.close();
        }
    }

    /**
     * Every journey starts on an empty engine.
     *
     * <p>The Spring context — and therefore the database — is shared by every test in the module,
     * so without this each test sees the processes of the ones before it. That does not merely add
     * noise: assertions like "the list shows one item" become order-dependent, which is the kind of
     * failure that only appears once the suite is big enough to be worth having.
     *
     * <p>The definitions are left alone. They are the seed the engine imported at startup, and
     * re-importing them per test would cost more than it proves.
     */
    @BeforeEach
    void emptyTheEngine() {
        for (var table : List.of("outbox_message_entity", "log_message_entity", "resource_entity",
                "step_execution_entity", "process_entity",
                // The test worker's own two, for the same reason: its pages are in this shell, and
                // a row left by the previous journey turns "the list shows what I recorded" into
                // an assertion about test order.
                "received_task", "task_override")) {
            jdbc.execute("delete from " + table);
        }
    }

    @BeforeEach
    void openPage() {
        // A fresh context per test: no cookies, no storage and no leftover client-side routing
        // state carried from the previous journey.
        //
        // Wide on purpose. The process toolbar pages its buttons behind a "⋯" expander when they
        // do not fit, so a narrow window turns "is this action offered?" into "how many times do I
        // press the expander?" — a property of the window, not of the engine. This is the width of
        // the console an operator actually runs; the page object still copes with the expander for
        // whatever does not fit.
        page = browser.newContext(new Browser.NewContextOptions()
                .setViewportSize(1920, 1200)).newPage();
        page.onConsoleMessage(message -> {
            if ("error".equals(message.type())) {
                System.err.println("[browser console] " + message.text());
            }
        });
        page.onPageError(error -> System.err.println("[browser error] " + error));
        page.navigate("http://localhost:" + port + "/");
        ui = new WorkflowUi(page);
        ui.awaitLoaded();
    }

    /**
     * On the way out, keep what a failure needs. A browser test that fails in CI is unreadable
     * from a stack trace alone — the question is always "what was on screen?" — so every run
     * leaves a screenshot and the rendered HTML behind, and the console messages are echoed as
     * they happen because a UI failure is very often a JavaScript error nothing else reports.
     */
    @AfterEach
    void closePage(org.junit.jupiter.api.TestInfo testInfo) {
        if (page == null) {
            return;
        }
        try {
            var name = testInfo.getTestClass().map(Class::getSimpleName).orElse("unknown")
                    + "." + testInfo.getTestMethod().map(java.lang.reflect.Method::getName).orElse("unknown");
            var dir = java.nio.file.Path.of("target", "ui-e2e");
            java.nio.file.Files.createDirectories(dir);
            page.screenshot(new Page.ScreenshotOptions()
                    .setPath(dir.resolve(name + ".png")).setFullPage(true));
            java.nio.file.Files.writeString(dir.resolve(name + ".html"), page.content());
        } catch (Exception e) {
            System.err.println("Could not capture UI diagnostics: " + e.getMessage());
        } finally {
            page.context().close();
        }
    }

    /** How many step executions a process has, which is how a re-drive shows up in the database. */
    protected long stepExecutionCount(String processId) {
        return jdbc.queryForObject(
                "select count(*) from step_execution_entity where process_id = ?", Long.class, processId);
    }

    /**
     * Waits for something the browser cannot see. Playwright's assertions already retry, so this is
     * only for the few checks that read the engine's own state rather than the page.
     */
    protected void awaitUntil(java.time.Duration timeout, java.util.function.BooleanSupplier condition) {
        var deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        throw new AssertionError("Condition still false after " + timeout);
    }

    /**
     * Starts a process the way anything outside the engine does, and returns its id — which is
     * what the list renders as the link into the detail, so it is what a test clicks.
     */
    protected String startProcess(String workflowDefinitionId, String businessKey, Variable... variables) {
        processUpstreamEvents.handle(new ProcessUpstreamEventCommand(
                new ProcessCreationRequested(workflowDefinitionId, businessKey, List.of(variables))));
        return processes.findByBusinessKey(businessKey)
                .orElseThrow(() -> new IllegalStateException(
                        "The engine did not create a process for business key " + businessKey))
                .id();
    }
}

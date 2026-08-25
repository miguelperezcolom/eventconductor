package io.mateu.demo.e2e;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.WaitForSelectorState;
import com.microsoft.playwright.options.WaitUntilState;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * End-to-end test of the booking-saga demo journey a real operator lives:
 *
 * <ol>
 *   <li>Enter the console and log in through Keycloak.</li>
 *   <li>Create a reservation; a payment-verification task is raised and the header widget lights up.</li>
 *   <li>Follow the widget to the task list, claim the task and complete it:
 *       <ul>
 *         <li>payment received → the reservation ends <b>Confirmed</b>;</li>
 *         <li>payment not received → the reservation ends <b>Cancelled</b>.</li>
 *       </ul></li>
 *   <li>Create a reservation and do nothing: after the step timeout the task is gone from the widget
 *       and the reservation is <b>Cancelled</b> (native on-timeout routing to {@code cancelar-reserva}).</li>
 * </ol>
 *
 * <p>The whole stack runs in docker-compose under {@code ~/IdeaProjects/ec-demo-local}; the browser
 * enters through the api-gw at {@link #BASE}. Keycloak is reached at {@code keycloak.local:8080} from
 * both the browser (host-resolver rule below) and the containers (compose network alias), so the
 * token's {@code iss} claim matches on both sides.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BookingJourneyE2eTest {

  private static final String BASE = envOr("BASE_URL", "http://localhost:8191");
  private static final String USER = envOr("KC_USER", "demo");
  private static final String PASS = envOr("KC_PASS", "demo");
  // Must match `timeout:` on the verify-payment USER_TASK in wf-repo/verify-booking-payment.ec.
  private static final long TIMEOUT_MS = Long.parseLong(envOr("WF_TIMEOUT_MS", "30000"));

  private Playwright playwright;
  private Browser browser;
  private BrowserContext context;
  private Page page;

  private final String runId = Long.toString(System.currentTimeMillis(), 36);

  @BeforeAll
  void setUp() {
    playwright = Playwright.create();
    boolean headed = "1".equals(System.getenv("HEADED")) || "true".equals(System.getenv("HEADED"));
    browser =
        playwright
            .chromium()
            .launch(
                new BrowserType.LaunchOptions()
                    .setHeadless(!headed)
                    // keycloak.local resolves to the published Keycloak port for the browser; the
                    // containers reach the same host through the compose network alias.
                    .setArgs(List.of("--host-resolver-rules=MAP keycloak.local 127.0.0.1")));
    context = browser.newContext();
    context.setDefaultTimeout(20_000);
    page = context.newPage();
    login();
    drainExistingTasks();
  }

  /**
   * Complete any tasks already pending when the suite starts, so each journey begins from a clean
   * slate. The header notice is global (it lights up for any pending task, not just this run's), and
   * {@link #runFirstPendingTask()} takes the first pending task — both only behave deterministically
   * when no unrelated tasks are outstanding (e.g. an orphan left by an earlier interrupted run).
   */
  private void drainExistingTasks() {
    for (int i = 0; i < 20; i++) {
      reloadHome();
      if (!hasPendingTasks()) return;
      openTasksFromWidget();
      page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Run").setExact(true))
          .first()
          .click();
      page.waitForURL("**/forms/task/**");
      Locator claim = page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Claim"));
      if (claim.count() > 0) {
        claim.click();
        assertThat(text("Payment received")).isVisible();
      }
      page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Complete")).click();
      page.waitForURL("**/forms/tasks");
    }
    throw new AssertionError("Could not drain pre-existing tasks — still pending after 20 completions");
  }

  /** True when the header notice shows pending tasks. Waits for the widget to render first. */
  private boolean hasPendingTasks() {
    // The notice text lives in a shadow root (invisible to document.body.innerText), so wait on the
    // locator: either "You have tasks!" or "No tasks" becomes visible once the widget has rendered.
    Locator youHaveTasks = page.getByText("You have tasks!", new Page.GetByTextOptions().setExact(true));
    Locator noTasks = page.getByText("No tasks", new Page.GetByTextOptions().setExact(true));
    youHaveTasks
        .or(noTasks)
        .first()
        .waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE).setTimeout(15_000));
    return youHaveTasks.count() > 0;
  }

  @AfterAll
  void tearDown() {
    if (context != null) context.close();
    if (browser != null) browser.close();
    if (playwright != null) playwright.close();
  }

  @Test
  @Order(1)
  void paymentReceived_confirmsTheReservation() {
    String guest = "Confirm " + runId;
    createReservation(guest);

    // The task surfaces in the header widget.
    reloadHome();
    assertThat(text("You have tasks!")).isVisible();

    openTasksFromWidget();
    runFirstPendingTask();
    claimTask();
    setPaymentReceived(true);
    completeTask();

    assertEquals("Confirmed", settledStatusOf(guest), "reservation should be confirmed after payment received");
  }

  @Test
  @Order(2)
  void paymentNotReceived_cancelsTheReservation() {
    String guest = "Reject " + runId;
    createReservation(guest);

    reloadHome();
    assertThat(text("You have tasks!")).isVisible();

    openTasksFromWidget();
    runFirstPendingTask();
    claimTask();
    setPaymentReceived(false); // leave "Payment received" unchecked
    completeTask();

    assertEquals("Cancelled", settledStatusOf(guest), "reservation should be cancelled when payment not received");
  }

  @Test
  @Order(3)
  void noAction_timesOutAndCancelsTheReservation() {
    String guest = "Timeout " + runId;
    createReservation(guest);

    // The notice appears...
    reloadHome();
    assertThat(text("You have tasks!")).isVisible();

    // ...and after the step timeout (nobody acted) it is gone.
    page.waitForTimeout(TIMEOUT_MS + 12_000);
    reloadHome();
    assertThat(text("No tasks")).isVisible();

    assertEquals("Cancelled", settledStatusOf(guest), "reservation should be cancelled by the step timeout");
  }

  // --- journey steps -------------------------------------------------------

  private void login() {
    page.navigate(BASE, new Page.NavigateOptions().setWaitUntil(WaitUntilState.LOAD));
    page.waitForSelector("#username");
    page.fill("#username", USER);
    page.fill("#password", PASS);
    page.click("#kc-login");
    waitForConsole();
  }

  private void createReservation(String guest) {
    openBookingsList();
    page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("New")).click();
    page.locator("#leadName").getByRole(AriaRole.TEXTBOX).fill(guest);
    page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Save")).click();
    assertThat(text("Item saved successfully")).isVisible();
  }

  private void openTasksFromWidget() {
    text("You have tasks!").click();
    page.waitForURL("**/forms/tasks");
  }

  private void runFirstPendingTask() {
    page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Run").setExact(true))
        .first()
        .click();
    page.waitForURL("**/forms/task/**");
    assertThat(page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Claim"))).isVisible();
  }

  private void claimTask() {
    page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Claim")).click();
    // After claiming, the form fields become editable and Complete is offered.
    assertThat(text("Payment received")).isVisible();
  }

  private void setPaymentReceived(boolean received) {
    // The "Payment received" checkbox starts unchecked once the task is claimed; tick it only when
    // we mean to confirm. Leaving it unchecked drives the paymentReceived == 'false' branch.
    if (received) {
      text("Payment received").click();
    }
  }

  private void completeTask() {
    page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Complete")).click();
    page.waitForURL("**/forms/tasks");
  }

  // --- shared UI helpers ---------------------------------------------------

  private void openBookingsList() {
    reloadHome();
    page.locator("vaadin-menu-bar-button", new Page.LocatorOptions().setHasText("Booking")).click();
    // The submenu item — target the menu-bar-item, not the aria-live announcer that also says "Bookings".
    page.locator("vaadin-menu-bar-item", new Page.LocatorOptions().setHasText("Bookings")).first().click();
    assertThat(page.locator("vaadin-grid")).isVisible();
  }

  private static final List<String> STATUSES = List.of("Confirmed", "Cancelled", "Pending");

  /**
   * The reservation's status as shown in the Bookings grid, once it has settled to a terminal value.
   * The saga updates the booking asynchronously (task completion → Kafka → booking worker → read
   * model), and the grid loads its rows lazily, so this reloads and re-reads until the row shows a
   * non-{@code Pending} status. Columns are Id, Name, Created, Status (plus a leading selection
   * column whose cell is blank), so the status is read as the first known status value after the
   * guest's name cell rather than by a fixed offset.
   */
  private String settledStatusOf(String guest) {
    for (int attempt = 0; attempt < 12; attempt++) {
      openBookingsList();
      Locator name = page.getByText(guest, new Page.GetByTextOptions().setExact(true)).first();
      if (name.count() > 0) {
        assertThat(name).isVisible();
        List<String> cells = page.locator("vaadin-grid-cell-content").allTextContents();
        for (int i = 0; i < cells.size(); i++) {
          if (guest.equals(cells.get(i).trim())) {
            for (int j = i + 1; j < Math.min(i + 5, cells.size()); j++) {
              String v = cells.get(j).trim();
              if (STATUSES.contains(v)) {
                if (!"Pending".equals(v)) return v;
              }
            }
          }
        }
      }
      page.waitForTimeout(2500);
    }
    throw new AssertionError("Reservation '" + guest + "' never reached a terminal status in the grid");
  }

  private void reloadHome() {
    page.navigate(BASE, new Page.NavigateOptions().setWaitUntil(WaitUntilState.LOAD));
    waitForConsole();
  }

  /**
   * Exact, first match for a piece of visible text. Mateu mirrors many strings into an aria-live
   * announcer {@code <div>} as well as the real component, so a plain substring match trips
   * Playwright's strict mode; exact + first pins the visible element.
   */
  private Locator text(String s) {
    return page.getByText(s, new Page.GetByTextOptions().setExact(true)).first();
  }

  private void waitForConsole() {
    page.waitForFunction("() => document.body && document.body.innerText.includes('Demo Console')");
    page.waitForTimeout(1500); // let the header micro-frontend settle
  }

  private static String envOr(String key, String dflt) {
    String v = System.getenv(key);
    return v == null || v.isBlank() ? dflt : v;
  }
}

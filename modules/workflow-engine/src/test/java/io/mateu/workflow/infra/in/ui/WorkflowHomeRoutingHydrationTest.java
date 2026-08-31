package io.mateu.workflow.infra.in.ui;

import io.mateu.dtos.RunActionRqDto;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.workflow.infra.in.ui.adapters.WorkflowHomeAdapter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The home builds its dashboard when it is what you are looking at, and not when it is merely
 * deciding where you are going.
 *
 * <p>This app is a {@code RemoteMenu} in the console, so the shell owns the URL and every
 * navigation to any {@code /workflow/*} route hydrates this root first, only to resolve the route.
 * That hydration renders nothing — the response carries {@code component: null} — and it was
 * building the whole dashboard on the way: two aggregates over the process table plus every chart,
 * then discarded. On the reference deployment that hop took 777 ms to return 418 bytes.
 *
 * <p>The last case is the one that keeps this honest: a request with no routing information must
 * still build. A dashboard computed needlessly is a cost; a dashboard that is sometimes blank is a
 * bug, and the two are not worth trading.
 */
class WorkflowHomeRoutingHydrationTest {

    private final WorkflowHomeAdapter adapter = mock(WorkflowHomeAdapter.class);

    private WorkflowHome home() {
        when(adapter.fetch()).thenReturn(mock(io.mateu.workflow.infra.in.ui.adapters.WorkflowHomeData.class));
        return new WorkflowHome(adapter);
    }

    private static HttpRequest requestFor(String route, String consumedRoute) {
        var request = mock(HttpRequest.class);
        when(request.runActionRq()).thenReturn(RunActionRqDto.builder()
                .route(route)
                .consumedRoute(consumedRoute)
                .build());
        return request;
    }

    @Test
    @DisplayName("routing through to a sub-page builds nothing")
    void doesNotBuildTheDashboardWhileRouting() {
        // Exactly what the browser sends on the way to /workflow/processes: the route is set and
        // nothing of it has been consumed, so this root is not the destination.
        home().onHydrated(requestFor("/workflow/processes", ""));

        verify(adapter, never()).fetch();
    }

    @Test
    @DisplayName("visiting the home itself builds it")
    void buildsTheDashboardOnTheHomeItself() {
        home().onHydrated(requestFor("", ""));

        verify(adapter).fetch();
    }

    @Test
    @DisplayName("a route that has been fully consumed is this page")
    void buildsWhenTheRouteEndsHere() {
        home().onHydrated(requestFor("/workflow", "/workflow"));

        verify(adapter).fetch();
    }

    @Test
    @DisplayName("no routing information at all still builds")
    void buildsWhenThereIsNothingToGoOn() {
        home().onHydrated(null);

        verify(adapter).fetch();
    }

    @Test
    @DisplayName("a request with no action payload still builds")
    void buildsWhenTheRequestCarriesNoAction() {
        var request = mock(HttpRequest.class);
        when(request.runActionRq()).thenReturn(null);

        home().onHydrated(request);

        verify(adapter).fetch();
    }
}

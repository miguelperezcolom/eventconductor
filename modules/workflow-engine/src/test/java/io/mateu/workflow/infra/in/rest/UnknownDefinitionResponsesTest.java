package io.mateu.workflow.infra.in.rest;

import io.mateu.workflow.application.out.UnknownWorkflowDefinitionException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UnknownDefinitionResponsesTest {

    final UnknownWorkflowDefinitionException unknown = new UnknownWorkflowDefinitionException("booking");

    @Test
    void whileStartingItIsServiceUnavailableWithRetryAfter() {
        var availability = mock(ApplicationAvailability.class);
        when(availability.getReadinessState()).thenReturn(ReadinessState.REFUSING_TRAFFIC);

        var response = UnknownDefinitionResponses.of(unknown, availability);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
        assertThat(response.getBody().getDetail()).contains("'booking' is not loaded yet").contains("still starting");
    }

    @Test
    void onceReadyItIsNotFound() {
        var availability = mock(ApplicationAvailability.class);
        when(availability.getReadinessState()).thenReturn(ReadinessState.ACCEPTING_TRAFFIC);

        var response = UnknownDefinitionResponses.of(unknown, availability);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getHeaders().containsHeader(HttpHeaders.RETRY_AFTER)).isFalse();
        assertThat(response.getBody().getDetail()).contains("booking");
    }

    @Test
    void withoutAvailabilityItIsNotFound() {
        assertThat(UnknownDefinitionResponses.of(unknown, null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}

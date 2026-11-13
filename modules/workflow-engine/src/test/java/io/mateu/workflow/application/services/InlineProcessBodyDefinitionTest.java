package io.mateu.workflow.application.services;

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import io.mateu.workflow.domain.aggregates.WorkflowDefinition;
import io.mateu.workflow.domain.services.EmbeddedProcessDefinitions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A PROCESS step's inline body: what it compiles to, and the rules it is held to. */
class InlineProcessBodyDefinitionTest {

    private static final YAMLMapper YAML = new YAMLMapper();

    private WorkflowDefinitionValidator validator;

    @BeforeEach
    void setUp() throws Exception {
        validator = new WorkflowDefinitionValidator();
        validator.init();
    }

    private static WorkflowDefinition definition(String yaml) throws Exception {
        return YAML.readValue(yaml, WorkflowDefinition.class);
    }

    private static final String BOOKING = """
            id: booking
            name: Booking
            version: 7
            sensitiveVariables: [cardNumber]
            requiredScopes: [bookings:write]
            steps:
              - { id: start, type: START, name: Start }
              - id: rooms
                type: PROCESS
                name: Book one room
                preconditionStepId: start
                outputVariables: [roomBookingId]
                forEach: { items: rooms, as: room, output: roomBookings, outputElement: roomBookingId }
                steps:
                  - { id: start, type: START, name: Start }
                  - { id: create, type: ACTION, name: Create, topic: create-booking, preconditionStepId: start }
                  - id: extras
                    type: PROCESS
                    name: Extras
                    preconditionStepId: create
                    steps:
                      - { id: start, type: START, name: Start }
                      - { id: wait, type: WAIT_FOR_MESSAGE, name: Wait, preconditionStepId: start, messageName: extras-ready, correlationExpression: "${room}" }
                  - { id: end, type: END, name: End, preconditionStepId: extras }
              - { id: end, type: END, name: End, preconditionStepId: rooms }
            """;

    @Test
    void anInlineBodyIsAValidDefinition() throws Exception {
        var booking = definition(BOOKING);

        // Without the sensitive declaration, which needs an encryption key this test does not configure.
        assertThatNoException().isThrownBy(() -> validator.validate(booking.withSensitiveVariables(null)));
    }

    @Test
    void theBodyBecomesADefinitionNamedAfterItsStepVersionedWithItsParent() throws Exception {
        var booking = definition(BOOKING);
        var rooms = booking.steps().get(1);

        var embedded = EmbeddedProcessDefinitions.of(booking, rooms);

        assertThat(embedded.id()).isEqualTo("booking.rooms");
        assertThat(embedded.name()).isEqualTo("Book one room");
        assertThat(embedded.version()).isEqualTo(7);
        assertThat(embedded.steps()).extracting(s -> s.id()).containsExactly("start", "create", "extras", "end");
        assertThat(embedded.steps()).allSatisfy(s -> assertThat(s.workflowDefinitionId()).isEqualTo("booking.rooms"));
        // What the parent says about its data and who may touch it holds for the child, which holds that data.
        assertThat(embedded.sensitiveVariables()).containsExactly("cardNumber");
        assertThat(embedded.requiredScopes()).containsExactly("bookings:write");
        assertThat(EmbeddedProcessDefinitions.childDefinitionIdOf("booking", rooms)).isEqualTo("booking.rooms");
    }

    @Test
    void nestedBodiesComposeTheirIdsAndAreFoundByWhatScansForWaits() throws Exception {
        var all = EmbeddedProcessDefinitions.withEmbedded(List.of(definition(BOOKING)));

        assertThat(all).extracting(WorkflowDefinition::id)
                .containsExactly("booking", "booking.rooms", "booking.rooms.extras");
        assertThat(all.get(2).steps()).anySatisfy(step -> assertThat(step.messageName()).isEqualTo("extras-ready"));
    }

    @Test
    void anInlineBodyRoundTripsThroughJson() throws Exception {
        var booking = definition(BOOKING);

        var json = io.mateu.core.infra.JsonSerializer.toJson(booking);
        var back = io.mateu.core.infra.JsonSerializer.pojoFromJson(json, WorkflowDefinition.class);

        assertThat(back.steps().get(1).steps()).hasSize(4);
        assertThat(back.steps().get(1).steps().get(2).steps()).hasSize(2);
        // A step without a body writes none: definitions without bodies hash as before.
        assertThat(io.mateu.core.infra.JsonSerializer.toJson(booking.steps().get(0))).doesNotContain("\"steps\"");
    }

    @Test
    void aProcessStepDeclaresAChildOrABodyNotBoth() throws Exception {
        var both = definition("""
                id: both
                name: Both
                steps:
                  - { id: start, type: START, name: Start }
                  - id: p
                    type: PROCESS
                    name: P
                    preconditionStepId: start
                    childWorkflowDefinitionId: other
                    steps:
                      - { id: start, type: START, name: Start }
                """);
        var neither = definition("""
                id: neither
                name: Neither
                steps:
                  - { id: start, type: START, name: Start }
                  - { id: p, type: PROCESS, name: P, preconditionStepId: start }
                """);

        assertThatThrownBy(both::checkInvariants).hasMessageContaining("declares both a childWorkflowDefinitionId and inline steps");
        assertThatThrownBy(neither::checkInvariants).hasMessageContaining("must define a childWorkflowDefinitionId or inline steps");
        // The schema says the same.
        assertThatThrownBy(() -> validator.validate(both.withSteps(both.steps())))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void onlyAProcessStepHasABody() throws Exception {
        var action = definition("""
                id: action-body
                name: Action with a body
                steps:
                  - { id: start, type: START, name: Start }
                  - id: a
                    type: ACTION
                    name: A
                    topic: t
                    preconditionStepId: start
                    steps:
                      - { id: start, type: START, name: Start }
                """);

        assertThatThrownBy(action::checkInvariants).hasMessageContaining("declares inline steps, which only a PROCESS step has");
    }

    @Test
    void aBrokenBodyIsReportedWhereItIs() throws Exception {
        var broken = definition("""
                id: booking
                name: Booking
                steps:
                  - { id: start, type: START, name: Start }
                  - id: rooms
                    type: PROCESS
                    name: Rooms
                    preconditionStepId: start
                    steps:
                      - { id: start, type: START, name: Start }
                      - id: extras
                        type: PROCESS
                        name: Extras
                        preconditionStepId: start
                        steps:
                          - { id: start, type: START, name: Start }
                          - { id: orphan, type: ACTION, name: Orphan, topic: t }
                """);

        assertThatThrownBy(broken::checkInvariants)
                .hasMessageStartingWith("booking.rooms.extras: ")
                .hasMessageContaining("Step 'orphan' has no preconditions");
    }

    @Test
    void anAgentInsideABodyIsCheckedAgainstTheCatalogue() throws Exception {
        var withAgent = definition("""
                id: agentic
                name: Agentic
                steps:
                  - { id: start, type: START, name: Start }
                  - id: p
                    type: PROCESS
                    name: P
                    preconditionStepId: start
                    steps:
                      - { id: start, type: START, name: Start }
                      - { id: ask, type: AGENT, name: Ask, preconditionStepId: start, agent: { ref: helper } }
                """);

        assertThatThrownBy(() -> WorkflowDefinitionValidator.requirePublishedAgents(withAgent, null))
                .hasMessageContaining("catalogue is not configured");
    }
}

package io.mateu.workflow.application.services;

import io.mateu.uidl.data.FieldDataType;
import io.mateu.uidl.data.FieldStereotype;
import io.mateu.workflow.application.out.FormRepository;
import io.mateu.workflow.domain.Field;
import io.mateu.workflow.domain.Form;
import io.mateu.workflow.security.AuthorizationContext;
import io.mateu.workflow.security.CallerResolver;
import io.mateu.workflow.security.FlowAuthorizationDeniedException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;

/**
 * AUTHZ-TASK-01..07 — who may work on a task.
 *
 * <p>The form says which scopes and roles the work needs, and the same answer has to govern all
 * three places a person meets a task: the list they are shown, the claim, and the completion. What
 * these pin above all is that the list is a convenience and not the boundary — the refusals stand on
 * their own, so an id obtained some other way buys nothing.
 */
@ExtendWith(MockitoExtension.class)
class TaskAuthorizationTest {

    @Mock CallerResolver callerResolver;
    @Mock FormRepository formRepository;

    private TaskAuthorization enforcing(boolean enabled) {
        var authorization = new TaskAuthorization(callerResolver, formRepository);
        authorization.enabled = enabled;
        return authorization;
    }

    private static Form form(String id, List<String> scopes, List<String> roles) {
        return new Form(id, "Form " + id, "",
                List.of(new Field("note", "Note", FieldDataType.string, FieldStereotype.regular, false, "")),
                scopes, roles);
    }

    private void callerIs(AuthorizationContext caller) {
        lenient().when(callerResolver.current()).thenReturn(caller);
    }

    private void catalogueIs(Form... forms) {
        lenient().when(formRepository.findAll()).thenReturn(List.of(forms));
    }

    /** AUTHZ-TASK-01. The list is narrowed to the work this person can actually do. */
    @Test
    void onlyTheFormsTheCallerHoldsTheRequirementsForArePermitted() {
        catalogueIs(form("open", List.of(), List.of()),
                form("finance", List.of("payments:approve"), List.of()),
                form("ops", List.of(), List.of("operator")));
        callerIs(new AuthorizationContext("ana", List.of(), List.of("operator")));

        assertThat(enforcing(true).permittedFormIds()).containsExactlyInAnyOrder("open", "ops");
    }

    /**
     * AUTHZ-TASK-02. An unidentified person sees only the unrestricted work. Not nothing — a
     * deployment with no identity at all still has tasks to do — and not everything.
     */
    @Test
    void anUnidentifiedCallerSeesOnlyWhatRequiresNothing() {
        catalogueIs(form("open", List.of(), List.of()), form("finance", List.of("payments:approve"), List.of()));
        callerIs(null);

        assertThat(enforcing(true).permittedFormIds()).containsExactly("open");
    }

    /** AUTHZ-TASK-03. Claiming and completing are refused by name, saying what was missing. */
    @Test
    void theRefusalNamesWhatIsMissingAndWhatWasBeingAttempted() {
        callerIs(new AuthorizationContext("ana", List.of(), List.of()));

        assertThatThrownBy(() -> enforcing(true)
                .refuseIfCallerMayNot("claim", form("finance", List.of("payments:approve"), List.of()), "t-1"))
                .isInstanceOf(FlowAuthorizationDeniedException.class)
                .hasMessageContaining("claim task t-1")
                .hasMessageContaining("payments:approve")
                .hasMessageContaining("ana");
    }

    /** AUTHZ-TASK-04. Requires-all: one of two held is not enough. */
    @Test
    void holdingSomeOfWhatIsRequiredIsNotHoldingIt() {
        callerIs(new AuthorizationContext("ana", List.of("payments:approve"), List.of()));

        assertThatThrownBy(() -> enforcing(true).refuseIfCallerMayNot("complete",
                form("finance", List.of("payments:approve"), List.of("finance")), "t-1"))
                .isInstanceOf(FlowAuthorizationDeniedException.class)
                .hasMessageContaining("finance");
    }

    /** AUTHZ-TASK-05. A form that requires nothing is work anybody can pick up, as it always was. */
    @Test
    void aFormThatRequiresNothingRefusesNobody() {
        callerIs(null);

        assertThatCode(() -> enforcing(true)
                .refuseIfCallerMayNot("complete", form("open", List.of(), List.of()), "t-1"))
                .doesNotThrowAnyException();
    }

    /**
     * AUTHZ-TASK-06. A task naming a form that is not in the catalogue carries no requirement,
     * because a form that is not there cannot state one. Nothing is granted by this: the submission
     * path refuses such a task on its own account.
     */
    @Test
    void aTaskWhoseFormIsGoneIsNotRefusedHere() {
        assertThatCode(() -> enforcing(true).refuseIfCallerMayNot("complete", null, "t-1"))
                .doesNotThrowAnyException();
    }

    /** AUTHZ-TASK-07. Off is off: no narrowing, no refusals, exactly as before forms could declare. */
    @Test
    void nothingIsNarrowedOrRefusedWhileTheFeatureIsOff() {
        callerIs(null);
        var authorization = enforcing(false);

        assertThat(authorization.enabled()).isFalse();
        assertThatCode(() -> authorization.refuseIfCallerMayNot("complete",
                form("finance", List.of("payments:approve"), List.of()), "t-1"))
                .doesNotThrowAnyException();
    }
}

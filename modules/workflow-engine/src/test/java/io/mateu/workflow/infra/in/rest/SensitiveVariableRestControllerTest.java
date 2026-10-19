package io.mateu.workflow.infra.in.rest;

import io.mateu.workflow.application.services.ProcessOperationsService;
import io.mateu.workflow.application.services.SensitiveFixtures;
import io.mateu.workflow.application.services.SensitiveVariableRevealService;
import io.mateu.workflow.application.usecases.process.operate.OperatorAudit;
import io.mateu.workflow.infra.config.MessageApiProperties;
import io.mateu.workflow.security.AuthorizationContext;
import io.mateu.workflow.security.CallerResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static io.mateu.workflow.application.services.SensitiveFixtures.CARD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Masked everywhere, revealed only on request — to a reveal role, with a reason, audited (REST and MCP). */
class SensitiveVariableRestControllerTest {

    final ProcessOperationsService processes = mock(ProcessOperationsService.class);
    final OperatorAudit audit = mock(OperatorAudit.class);
    final AtomicReference<AuthorizationContext> caller = new AtomicReference<>();
    final SensitiveVariableRevealService reveal = new SensitiveVariableRevealService(processes, audit, List.of("pci-auditor", " "));
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        var process = SensitiveFixtures.process("p1");
        when(processes.get(eq("p1"), any())).thenReturn(process);
        var apiProperties = new MessageApiProperties();
        apiProperties.setAllowAnonymous(true);
        var beans = new StaticListableBeanFactory();
        beans.addBean("caller", (CallerResolver) caller::get);
        mvc = MockMvcBuilders.standaloneSetup(new SensitiveVariableRestController(reveal, apiProperties,
                beans.getBeanProvider(CallerResolver.class))).build();
    }

    @Test
    void theProcessViewMasksSensitiveValues() {
        var view = ProcessApi.ProcessView.of(SensitiveFixtures.process("p2"));
        assertThat(view.variables()).containsEntry("cardNumber", "••••").containsEntry("amount", "120");
        assertThat(ProcessApi.variablesOf(SensitiveFixtures.process("p3"))).containsEntry("patient", "••••");
    }

    @Test
    void aRevealRoleWithAReasonGetsTheValueAndLeavesAnAuditLine() throws Exception {
        caller.set(new AuthorizationContext("alice", List.of(), List.of("pci-auditor")));
        mvc.perform(post("/workflow/api/processes/p1/variables/cardNumber/reveal")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Chargeback CB-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.value").value(CARD))
                .andExpect(jsonPath("$.name").value("cardNumber"));
        verify(audit).performed(any(io.mateu.workflow.domain.aggregates.Process.class), isNull(),
                eq(SensitiveVariableRevealService.REVEAL_VARIABLE), eq("alice"), eq("Chargeback CB-1"), eq("cardNumber"));
        assertThat(reveal.revealRoles()).containsExactly("pci-auditor");
    }

    @Test
    void withoutTheRoleItIsRefusedAndTheRefusalIsAudited() throws Exception {
        caller.set(new AuthorizationContext("bob", List.of(), List.of("operator")));
        mvc.perform(post("/workflow/api/processes/p1/variables/cardNumber/reveal")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"curious\"}"))
                .andExpect(status().isForbidden());
        verify(audit).refused(eq("p1"), isNull(), eq(SensitiveVariableRevealService.REVEAL_VARIABLE), eq("bob"),
                eq("curious"), contains("reveal roles"));
        verify(audit, never()).performed(any(io.mateu.workflow.domain.aggregates.Process.class), any(), anyString(), any(), any(), any());
        // An API key alone identifies nobody: no role, no reveal.
        caller.set(null);
        mvc.perform(post("/workflow/api/processes/p1/variables/cardNumber/reveal")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"x\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void aReasonIsRequiredAndOnlySensitiveVariablesAreRevealed() throws Exception {
        caller.set(new AuthorizationContext("alice", List.of(), List.of("pci-auditor")));
        mvc.perform(post("/workflow/api/processes/p1/variables/cardNumber/reveal"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/workflow/api/processes/p1/variables/amount/reveal")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"x\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/workflow/api/processes/p1/variables/authCode/reveal")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"x\"}"))
                .andExpect(status().isNotFound());
        when(processes.get(eq("nope"), any())).thenThrow(new ProcessOperationsService.ProcessNotFoundException("No process"));
        mvc.perform(post("/workflow/api/processes/nope/variables/cardNumber/reveal")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"x\"}"))
                .andExpect(status().isNotFound());
    }
}

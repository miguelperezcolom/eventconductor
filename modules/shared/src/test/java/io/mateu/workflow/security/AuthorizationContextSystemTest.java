package io.mateu.workflow.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mateu.workflow.dtos.events.integration.ProcessCreationRequested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** SYSTEM is recognised by identity, so a resolved caller cannot pass for it by name. */
class AuthorizationContextSystemTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void aLookAlikeIsNotTheSystemIdentity() {
        assertThat(AuthorizationContext.isSystem(AuthorizationContext.SYSTEM)).isTrue();
        assertThat(AuthorizationContext.isSystem(new AuthorizationContext("system", List.of(), List.of()))).isFalse();
        assertThat(AuthorizationContext.isSystem(null)).isFalse();
    }

    @Test
    void aSystemStartKeepsItsIdentityAcrossTheEnginesOwnTopics() throws Exception {
        var wire = JSON.writeValueAsString(new ProcessCreationRequested("report", "k", List.of(), null,
                AuthorizationContext.SYSTEM));
        var read = JSON.readValue(wire, ProcessCreationRequested.class);
        assertThat(AuthorizationContext.isSystem(read.caller())).isTrue();

        var user = JSON.readValue(JSON.writeValueAsString(new AuthorizationContext("ana", List.of("a"), List.of("r"))),
                AuthorizationContext.class);
        assertThat(user).isEqualTo(new AuthorizationContext("ana", List.of("a"), List.of("r")));
        assertThat(AuthorizationContext.isSystem(user)).isFalse();
    }
}

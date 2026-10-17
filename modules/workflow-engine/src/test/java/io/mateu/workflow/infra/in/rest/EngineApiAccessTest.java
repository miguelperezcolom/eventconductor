package io.mateu.workflow.infra.in.rest;

import io.mateu.workflow.infra.config.MessageApiProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/** Who may call the engine's REST API: a key, the application's own security, or an explicit opt-in. */
class EngineApiAccessTest {

    /** Stands in for Spring Security's SecurityFilterChain, which the engine does not depend on. */
    interface FakeFilterChain {
    }

    private static MessageApiProperties properties(String key, boolean anonymous, boolean secured) {
        var properties = new MessageApiProperties();
        properties.setApiKey(key);
        properties.setAllowAnonymous(anonymous);
        properties.setSecuredByFilterChain(secured);
        return properties;
    }

    private static void assertRefused(MessageApiProperties properties, String received, String reason) {
        assertThatThrownBy(() -> EngineApiAccess.verify(properties, received))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
                    assertThat(e.getReason()).contains(reason);
                });
    }

    @Test
    void withoutAKeyOrSecurityAnonymousRequestsAreRefusedByDefault() {
        assertRefused(properties(null, false, false), null, "allow-anonymous");
        assertRefused(properties(" ", false, false), "anything", "allow-anonymous");
    }

    @Test
    void anonymousRequestsComeInOnlyWhenOptedIn() {
        assertThatCode(() -> EngineApiAccess.verify(properties(null, true, false), null)).doesNotThrowAnyException();
    }

    @Test
    void theApplicationsOwnSecurityIsEnough() {
        assertThatCode(() -> EngineApiAccess.verify(properties(null, false, true), null)).doesNotThrowAnyException();
    }

    @Test
    void aConfiguredKeyMustMatchWhateverElseIsSet() {
        assertThatCode(() -> EngineApiAccess.verify(properties("k3y", false, false), "k3y")).doesNotThrowAnyException();
        assertRefused(properties("k3y", true, true), null, "X-Api-Key");
        assertRefused(properties("k3y", true, true), "wrong", "X-Api-Key");
    }

    @Test
    void atStartupItRecordsWhetherAFilterChainProtectsTheApplication() {
        var beans = new StaticListableBeanFactory();
        beans.addBean("messages", mock(MessageRestController.class));
        assertThat(EngineApiAccess.hasFilterChain(beans, FakeFilterChain.class.getName())).isFalse();
        assertThat(EngineApiAccess.hasFilterChain(beans, EngineApiAccess.SECURITY_FILTER_CHAIN))
                .as("Spring Security not on the classpath").isFalse();
        beans.addBean("chain", new FakeFilterChain() { });
        assertThat(EngineApiAccess.hasFilterChain(beans, FakeFilterChain.class.getName())).isTrue();

        var properties = properties(null, false, false);
        new EngineApiAccess(properties, beans).afterSingletonsInstantiated();
        assertThat(properties.isSecuredByFilterChain()).as("Spring Security's own type is not on this classpath").isFalse();
        assertThat(EngineApiAccess.engineApiPresent(beans)).isTrue();
        assertThat(EngineApiAccess.engineApiPresent(new StaticListableBeanFactory())).isFalse();
    }
}

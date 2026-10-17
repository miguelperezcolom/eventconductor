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

    private static java.util.List<String> startupLog(MessageApiProperties properties, StaticListableBeanFactory beans) {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(EngineApiAccess.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            new EngineApiAccess(properties, beans).afterSingletonsInstantiated();
        } finally {
            logger.detachAppender(appender);
        }
        return appender.list.stream().map(e -> e.getLevel() + " " + e.getFormattedMessage()).toList();
    }

    private static StaticListableBeanFactory withControllers() {
        var beans = new StaticListableBeanFactory();
        beans.addBean("messages", mock(MessageRestController.class));
        return beans;
    }

    /** At startup the engine says which rule applies, naming the setting that changes it. */
    @Test
    void atStartupTheLogSaysHowTheApiIsProtected() {
        assertThat(startupLog(properties(null, false, false), withControllers())).singleElement().asString()
                .startsWith("WARN").contains("refuses every request", "workflow.message-api.api-key", "allow-anonymous");
        assertThat(startupLog(properties(null, true, false), withControllers())).singleElement().asString()
                .startsWith("WARN").contains("is OPEN");
        assertThat(startupLog(properties("k3y", false, false), withControllers())).isEmpty();
        assertThat(startupLog(properties(null, false, false), new StaticListableBeanFactory()))
                .as("no engine API, nothing to say").isEmpty();
    }

    @Test
    void anyOfTheEnginesControllersCountsAsTheApi() {
        for (var controller : java.util.List.<Class<?>>of(EventRestController.class, ScheduleRestController.class,
                SyncInvocationController.class, MessageRestController.class)) {
            var beans = new StaticListableBeanFactory();
            beans.addBean("controller", mock(controller));
            assertThat(EngineApiAccess.engineApiPresent(beans)).as(controller.getSimpleName()).isTrue();
        }
    }
}

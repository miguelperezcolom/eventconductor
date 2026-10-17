package io.mateu.workflow.infra.in.rest;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** The startup warning for an engine API nobody has put a key or a security filter chain in front of. */
class OpenEngineApiWarningTest {

    /** Stands in for Spring Security's SecurityFilterChain, which the engine does not depend on. */
    interface FakeFilterChain {
    }

    private static StaticListableBeanFactory withControllers() {
        var beans = new StaticListableBeanFactory();
        beans.addBean("messages", mock(MessageRestController.class));
        return beans;
    }

    @Test
    void noKeyAndNoSecurityIsOpen() {
        assertThat(OpenEngineApiWarning.apiIsOpen(null, withControllers(), FakeFilterChain.class.getName())).isTrue();
        assertThat(OpenEngineApiWarning.apiIsOpen(" ", withControllers(), OpenEngineApiWarning.SECURITY_FILTER_CHAIN))
                .as("Spring Security not on the classpath").isTrue();
    }

    @Test
    void anApiKeyOrASecurityFilterChainCloseIt() {
        assertThat(OpenEngineApiWarning.apiIsOpen("k3y", withControllers(), FakeFilterChain.class.getName())).isFalse();
        var secured = withControllers();
        secured.addBean("chain", new FakeFilterChain() { });
        assertThat(OpenEngineApiWarning.apiIsOpen(null, secured, FakeFilterChain.class.getName())).isFalse();
    }

    @Test
    void withoutTheEnginesControllersThereIsNothingToWarnAbout() {
        assertThat(OpenEngineApiWarning.apiIsOpen(null, new StaticListableBeanFactory(), FakeFilterChain.class.getName()))
                .isFalse();
    }
}

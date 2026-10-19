package io.mateu.workflow.autoconfigure;

import io.mateu.workflow.time.EngineClock;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import java.time.Clock;

/**
 * The engine's clock, as a bean: {@value #CLOCK_BEAN}, {@link Clock#systemDefaultZone()} unless the
 * application (or the {@code workflow-test} kit) defines a bean of that name, and installed into
 * {@link EngineClock}, where every business-time read in the engine goes.
 *
 * <p>Declared with {@code defaultCandidate = false}: it is injected only where asked for by name, so
 * an application that autowires a {@link Clock} by type — its own, or none — sees exactly what it
 * saw before the engine had one.
 */
@AutoConfiguration
public class WorkflowClockAutoConfiguration {

    public static final String CLOCK_BEAN = "eventconductorClock";

    @Bean(name = CLOCK_BEAN, defaultCandidate = false)
    @ConditionalOnMissingBean(name = CLOCK_BEAN)
    public Clock eventconductorClock() {
        return EngineClock.system();
    }

    @Bean
    public EngineClockInstaller eventconductorClockInstaller(@Qualifier(CLOCK_BEAN) Clock clock) {
        return new EngineClockInstaller(clock);
    }

    /** Puts the clock bean into {@link EngineClock} while the context lives, and takes it out after. */
    public static final class EngineClockInstaller implements InitializingBean, DisposableBean {

        private final Clock clock;

        EngineClockInstaller(Clock clock) {
            this.clock = clock;
        }

        @Override
        public void afterPropertiesSet() {
            EngineClock.set(clock);
        }

        @Override
        public void destroy() {
            if (EngineClock.get() == clock) {
                EngineClock.reset();
            }
        }
    }
}

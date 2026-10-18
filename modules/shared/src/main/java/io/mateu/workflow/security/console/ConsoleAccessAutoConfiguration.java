package io.mateu.workflow.security.console;

import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * The consoles' access rule ({@link ConsoleAccess}) and the {@code workflow.ui.enabled} switch
 * ({@link ConsoleUiSwitch}), for every servlet application that carries an EventConductor module.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(WebMvcConfigurer.class)
public class ConsoleAccessAutoConfiguration {

    @Bean
    static ConsoleUiSwitch eventConductorConsoleUiSwitch() {
        return new ConsoleUiSwitch();
    }

    @Bean
    ConsoleAccess eventConductorConsoleAccess(ListableBeanFactory beans, Environment environment) {
        return new ConsoleAccess(beans, environment);
    }

    @Bean
    WebMvcConfigurer eventConductorConsoleAccessConfigurer(ConsoleAccess access) {
        var interceptor = new ConsoleAccessInterceptor(access);
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(interceptor);
            }
        };
    }
}

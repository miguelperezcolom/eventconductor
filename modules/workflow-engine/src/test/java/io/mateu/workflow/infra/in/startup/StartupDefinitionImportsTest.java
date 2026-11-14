package io.mateu.workflow.infra.in.startup;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class StartupDefinitionImportsTest {

    static final List<String> CALLS = new CopyOnWriteArrayList<>();

    @Order(0)
    static class Definitions implements StartupDefinitionImport {
        @Override
        public void importDefinitions() {
            CALLS.add("definitions");
        }
    }

    @Order(-100)
    static class Contracts implements StartupDefinitionImport {
        @Override
        public void importDefinitions() {
            CALLS.add("contracts");
        }
    }

    static class Directory implements StartupDefinitionImport {
        @Override
        public void importDefinitions() {
            CALLS.add("directory");
        }
    }

    /** Stands for the web server: a lifecycle in the phase the servlet web server starts in. */
    static class WebServer implements org.springframework.context.SmartLifecycle {
        boolean running;

        @Override
        public void start() {
            CALLS.add("web-server");
            running = true;
        }

        @Override
        public void stop() {
            running = false;
        }

        @Override
        public boolean isRunning() {
            return running;
        }

        @Override
        public int getPhase() {
            return Integer.MAX_VALUE - 2048;
        }
    }

    @Configuration
    static class Config {
        @Bean Directory directory() {
            return new Directory();
        }

        @Bean Definitions definitions() {
            return new Definitions();
        }

        @Bean Contracts contracts() {
            return new Contracts();
        }

        @Bean WebServer webServer() {
            return new WebServer();
        }

        @Bean StartupDefinitionImports startupDefinitionImports(
                org.springframework.beans.factory.ObjectProvider<StartupDefinitionImport> imports) {
            return new StartupDefinitionImports(imports);
        }
    }

    @Test
    void runsEveryImportInOrderBeforeTheWebServerStarts() {
        CALLS.clear();
        try (var context = new AnnotationConfigApplicationContext(Config.class)) {
            assertThat(CALLS).containsExactly("contracts", "definitions", "directory", "web-server");
            var lifecycle = context.getBean(StartupDefinitionImports.class);
            assertThat(lifecycle.isRunning()).isTrue();
            assertThat(lifecycle.getPhase()).isLessThan(Integer.MAX_VALUE - 2048);
            lifecycle.stop();
            assertThat(lifecycle.isRunning()).isFalse();
        }
    }
}

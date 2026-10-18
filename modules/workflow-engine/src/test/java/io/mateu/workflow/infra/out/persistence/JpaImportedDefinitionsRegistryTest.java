package io.mateu.workflow.infra.out.persistence;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Import provenance survives a restart: what a source imported is read back by a registry built over
 * a fresh context on the same (migrated) database — which is what lets the first import after a
 * restart prune a definition removed from the source while the engine was down.
 */
class JpaImportedDefinitionsRegistryTest {

    final DriverManagerDataSource dataSource = new DriverManagerDataSource(
            "jdbc:h2:mem:provenance-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL", "sa", "");
    AnnotationConfigApplicationContext context;

    {
        PostgresTestDatabase.migrate(dataSource);
    }

    @AfterEach
    void close() {
        if (context != null) {
            context.close();
        }
    }

    private JpaImportedDefinitionsRegistry boot() {
        if (context != null) {
            context.close();
        }
        context = AnalyticsJpaTestContext.open(dataSource);
        var registry = new JpaImportedDefinitionsRegistry(context.getBean(ImportedDefinitionSourceEntityRepository.class));
        // @Transactional is a proxy concern; the repository's own transactions are enough here.
        return registry;
    }

    @Test
    void whatASourceImportedIsStillKnownAfterARestart() {
        var before = boot();
        before.replace("workflow", "https://github.com/acme/flows.git#defs", Set.of("order", "refund"));
        before.replace("form", "https://github.com/acme/flows.git#defs", Set.of("approve"));

        var after = boot();
        assertThat(after.idsFor("workflow", "https://github.com/acme/flows.git#defs"))
                .containsExactlyInAnyOrder("order", "refund");
        // Namespaces and sources are kept apart.
        assertThat(after.idsFor("form", "https://github.com/acme/flows.git#defs")).containsExactly("approve");
        assertThat(after.idsFor("workflow", "/srv/flows")).isEmpty();
    }

    @Test
    void replacingOverwritesAndEmptyingForgetsTheSource() {
        var registry = boot();
        registry.replace("workflow", "/srv/flows", Set.of("a", "b"));
        registry.replace("workflow", "/srv/flows", Set.of("b"));
        assertThat(registry.idsFor("workflow", "/srv/flows")).containsExactly("b");

        registry.moveSource("workflow", "/srv/flows", "/srv/flows-v2");
        assertThat(registry.idsFor("workflow", "/srv/flows")).isEmpty();
        assertThat(registry.idsFor("workflow", "/srv/flows-v2")).containsExactly("b");
        assertThat(context.getBean(ImportedDefinitionSourceEntityRepository.class).count()).isEqualTo(1);
    }
}

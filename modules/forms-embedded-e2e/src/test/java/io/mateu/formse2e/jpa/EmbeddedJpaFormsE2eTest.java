package io.mateu.formse2e.jpa;

import io.mateu.formse2e.support.AbstractEmbeddedFormsJourney;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(classes = JpaFormsApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "workflow.mode=embedded",
                "workflow.persistence=jpa",
                "forms.persistence=jpa",
                "workflow.security.trust-forwarded-token=true",
                "forms.directory-import.directories[0]=src/test/resources/forms",
                "workflow.cron-enabled=false",
                "workflow.outbox-poll-interval-ms=50",
                "spring.datasource.url=jdbc:h2:mem:forms-embedded-e2e;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.jpa.hibernate.ddl-auto=create-drop",
                "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
                "spring.flyway.enabled=false",
                "forms.schema.enabled=false",
        })
class EmbeddedJpaFormsE2eTest extends AbstractEmbeddedFormsJourney {
}

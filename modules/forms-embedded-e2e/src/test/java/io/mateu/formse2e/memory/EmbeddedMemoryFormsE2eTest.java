package io.mateu.formse2e.memory;

import io.mateu.formse2e.support.AbstractEmbeddedFormsJourney;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(classes = MemoryFormsApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "workflow.mode=embedded",
                "workflow.persistence=memory",
                "workflow.security.trust-forwarded-token=true",
                "forms.directory-import.directories[0]=src/test/resources/forms",
                "workflow.cron-enabled=false",
        })
class EmbeddedMemoryFormsE2eTest extends AbstractEmbeddedFormsJourney {
}

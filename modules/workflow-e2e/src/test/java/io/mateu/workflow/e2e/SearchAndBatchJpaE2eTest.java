package io.mateu.workflow.e2e;

import io.mateu.workflow.application.services.BatchOperationService;
import io.mateu.workflow.application.services.ProcessOperationsService;
import io.mateu.workflow.e2e.support.AbstractJpaE2eTest;
import io.mateu.workflow.e2e.support.SearchAndBatchScenario;
import io.mateu.workflow.infra.out.persistence.ProcessSearchAttributeEntityRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Search attributes and batch operations on the durable path (embedded + JPA on H2): the attributes are
 * stored in process_search_attribute at every save, the listing narrows by them in SQL, and the batch
 * runner walks the filter under the database lock, its commands carried out through the outbox.
 */
class SearchAndBatchJpaE2eTest extends AbstractJpaE2eTest {

    @Autowired ProcessOperationsService processOperationsService;
    @Autowired BatchOperationService batchOperationService;
    @Autowired ProcessSearchAttributeEntityRepository attributeRows;

    @Test
    void processesAreFoundByTheirAttributesAndABatchActsOnExactlyThose() throws Exception {
        SearchAndBatchScenario.run("search-jpa-", worker, processRepository, processOperationsService,
                batchOperationService, key -> process(key).getStatus());
        // Five attributes per process, kept in step with its variables.
        assertThat(attributeRows.findAllByProcessId(process("search-jpa-0").getId())).hasSize(5);
    }
}

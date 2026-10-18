package io.mateu.workflow.e2e;

import io.mateu.workflow.application.services.BatchOperationService;
import io.mateu.workflow.application.services.ProcessOperationsService;
import io.mateu.workflow.e2e.support.AbstractE2eTest;
import io.mateu.workflow.e2e.support.SearchAndBatchScenario;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Search attributes and batch operations on the in-memory engine: the attributes are computed from each
 * process when the listing asks, and the batch runner works in-process.
 */
class SearchAndBatchE2eTest extends AbstractE2eTest {

    @Autowired ProcessOperationsService processOperationsService;
    @Autowired BatchOperationService batchOperationService;

    @Test
    void processesAreFoundByTheirAttributesAndABatchActsOnExactlyThose() throws Exception {
        SearchAndBatchScenario.run("search-mem-", worker, processRepository, processOperationsService,
                batchOperationService, key -> process(key).getStatus());
    }
}

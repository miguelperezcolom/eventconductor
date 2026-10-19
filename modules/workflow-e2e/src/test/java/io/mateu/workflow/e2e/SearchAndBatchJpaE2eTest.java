package io.mateu.workflow.e2e;

import io.mateu.workflow.application.services.BatchOperationService;
import io.mateu.workflow.application.services.ProcessOperationsService;
import io.mateu.workflow.e2e.support.AbstractJpaE2eTest;
import io.mateu.workflow.e2e.support.SearchAndBatchScenario;
import io.mateu.workflow.e2e.support.TestWorker;
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

    /**
     * Attributes belong to the process, not to a forEach iteration: the iteration's item ({@code room}) is
     * laid over the variables for that iteration only and is never indexed, while what the loop leaves in
     * the process (its output) is.
     */
    @Test
    void searchAttributesAreThoseOfTheProcessNotOfAForEachIteration() {
        worker.on("reserve", (req, cb, n) -> cb.complete(java.util.List.of(TestWorker.var("reservationId",
                "R-" + req.variables().stream().filter(v -> "room".equals(v.name())).findFirst().orElseThrow().value()))));

        createProcess("foreach-searchable", "fes-1", new io.mateu.workflow.dtos.Variable("guest", "G-1"),
                new io.mateu.workflow.dtos.Variable("rooms", "[\"101\",\"102\"]"));

        awaitStatus("fes-1", io.mateu.workflow.domain.aggregates.ProcessStatus.COMPLETED);
        var rows = attributeRows.findAllByProcessId(process("fes-1").getId()).stream()
                .map(io.mateu.workflow.infra.out.persistence.ProcessSearchAttributeEntity::toValue).toList();
        assertThat(rows).extracting(io.mateu.workflow.search.SearchAttributeValue::name)
                .containsExactlyInAnyOrder("guest", "reservations");
        assertThat(rows).filteredOn(v -> v.name().equals("reservations")).singleElement()
                .satisfies(v -> assertThat(v.keyword()).contains("R-101").contains("R-102"));
    }
}

package io.mateu.workflow.e2e.support;

import com.fasterxml.jackson.databind.JsonNode;
import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.services.BatchOperationService;
import io.mateu.workflow.application.services.ProcessOperationsService;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.infra.config.MessageApiProperties;
import io.mateu.workflow.infra.in.rest.BatchOperationRestController;
import io.mateu.workflow.security.CallerResolver;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static io.mateu.workflow.e2e.support.TestWorker.var;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Search attributes and a batch operation end to end, the same on every store: processes of
 * {@code search-orders} (which declares customerId, amount, placedOn, note and reviewed) are started,
 * change their variables in a step, and are found over the REST API by those attributes — then a batch
 * operation created over HTTP cancels exactly the ones a search-attribute filter matches.
 */
public final class SearchAndBatchScenario {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private SearchAndBatchScenario() {
    }

    public static void run(String prefix, TestWorker worker, ProcessRepository processRepository,
                           ProcessOperationsService operations, BatchOperationService batches,
                           Function<String, ProcessStatus> statusOfKey) throws Exception {
        var http = new ProcessesHttp(operations);
        worker.on("review", TestWorker.succeed(var("reviewed", "yes")));
        worker.on("hold", TestWorker.deferForever());

        var ids = new ArrayList<String>();
        for (var body : List.of(
                "{\"customerId\": \"C-9\", \"total\": 150, \"placedOn\": \"2026-10-01\", \"note\": \"Rush order\"}",
                "{\"customerId\": \"C-9\", \"total\": 50, \"placedOn\": \"2026-10-05\"}",
                "{\"customerId\": \"C-7\", \"total\": 500, \"placedOn\": \"2026-09-20\"}")) {
            var started = http.start("{\"definitionId\": \"search-orders\", \"businessKey\": \"" + prefix + ids.size()
                    + "\", \"variables\": " + body + "}");
            assertThat(started.getStatus()).isEqualTo(201);
            ids.add(ProcessesHttp.json(started).path("id").asText());
        }
        // The step changed the variables: "reviewed" is indexed once it has run.
        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(found(http, "attr.reviewed=yes&definitionId=search-orders&businessKey=" + prefix)).hasSize(3));

        assertThat(found(http, "attr.customerId=C-9&businessKey=" + prefix)).containsExactlyInAnyOrder(ids.get(0), ids.get(1));
        assertThat(found(http, "attr.amount.gte=100&businessKey=" + prefix)).containsExactlyInAnyOrder(ids.get(0), ids.get(2));
        assertThat(found(http, "attr.amount.gt=50&attr.amount.lt=500&businessKey=" + prefix)).containsExactly(ids.get(0));
        assertThat(found(http, "attr.placedOn.lte=2026-10-01&businessKey=" + prefix)).containsExactlyInAnyOrder(ids.get(0), ids.get(2));
        assertThat(found(http, "attr.note=rush&businessKey=" + prefix)).containsExactly(ids.get(0));
        var byQuery = ProcessesHttp.json(http.get("?size=50&businessKey=" + prefix, "query",
                "customerId = \"C-9\" AND amount > 100"));
        assertThat(byQuery.path("totalElements").asLong()).isEqualTo(1);
        assertThat(byQuery.path("content").get(0).path("id").asText()).isEqualTo(ids.get(0));
        assertThat(http.get("?attr.amount.gte=lots").getStatus()).isEqualTo(400);
        assertThat(http.get("", "query", "amount >").getStatus()).isEqualTo(400);
        var view = ProcessesHttp.json(http.get("/" + ids.get(0)));
        assertThat(view.path("searchAttributes").path("customerId").asText()).isEqualTo("C-9");
        assertThat(view.path("searchAttributes").path("amount").asText()).isEqualTo("150");

        // A batch over HTTP cancels what the attribute filter matches, and nothing else.
        var batchesHttp = batchesHttp(batches);
        var created = batchesHttp.perform(MockMvcRequestBuilders.post("/workflow/api/batch-operations")
                .header("X-Api-Key", ProcessesHttp.API_KEY).contentType("application/json")
                .content("{\"type\": \"cancel\", \"filter\": {\"definitionId\": \"search-orders\", \"businessKey\": \""
                        + prefix + "\", \"attributes\": {\"customerId\": \"C-9\"}}}"))
                .andReturn().getResponse();
        assertThat(created.getStatus()).isEqualTo(202);
        var batchId = ProcessesHttp.json(created).path("id").asText();
        assertThat(ProcessesHttp.json(created).path("matched").asLong()).isEqualTo(2);
        await().atMost(TIMEOUT).untilAsserted(() -> {
            var batch = json(batchesHttp, "/" + batchId);
            assertThat(batch.path("status").asText()).isEqualTo("COMPLETED");
            assertThat(batch.path("succeeded").asLong()).isEqualTo(2);
        });
        await().atMost(TIMEOUT).untilAsserted(() -> {
            assertThat(statusOfKey.apply(prefix + 0)).isEqualTo(ProcessStatus.CANCELLED);
            assertThat(statusOfKey.apply(prefix + 1)).isEqualTo(ProcessStatus.CANCELLED);
        });
        assertThat(statusOfKey.apply(prefix + 2)).isEqualTo(ProcessStatus.RUNNING);
        assertThat(json(batchesHttp, "").toString()).contains(batchId);
        assertThat(batchesHttp.perform(MockMvcRequestBuilders.post("/workflow/api/batch-operations/" + batchId + "/cancel")
                .header("X-Api-Key", ProcessesHttp.API_KEY)).andReturn().getResponse().getStatus()).isEqualTo(409);

        // Retry-all over what is now CANCELLED: the two come back; the running one is not in the filter.
        var retried = batches.create(io.mateu.workflow.domain.batch.BatchOperationType.RETRY,
                new io.mateu.workflow.domain.batch.BatchOperationFilter("search-orders", null,
                        java.util.Set.of(ProcessStatus.CANCELLED), null, null, prefix, null, false, null), null, null);
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(batches.get(retried.id()).orElseThrow().status())
                .isEqualTo(io.mateu.workflow.domain.batch.BatchOperationStatus.COMPLETED));
        assertThat(batches.get(retried.id()).orElseThrow().succeeded()).isEqualTo(2);
        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(statusOfKey.apply(prefix + 0)).isEqualTo(ProcessStatus.RUNNING));
        // Leave nothing running behind for the other tests.
        for (var id : ids) {
            try {
                operations.cancel(id, null);
            } catch (RuntimeException alreadyStopped) {
                // fine
            }
        }
    }

    private static List<String> found(ProcessesHttp http, String query) throws Exception {
        var page = ProcessesHttp.json(http.get("?size=50&" + query));
        var ids = new ArrayList<String>();
        page.path("content").forEach(row -> ids.add(row.path("id").asText()));
        return ids;
    }

    private static MockMvc batchesHttp(BatchOperationService batches) {
        var apiProperties = new MessageApiProperties();
        apiProperties.setApiKey(ProcessesHttp.API_KEY);
        return MockMvcBuilders.standaloneSetup(new BatchOperationRestController(batches, apiProperties,
                new StaticListableBeanFactory().getBeanProvider(CallerResolver.class))).build();
    }

    private static JsonNode json(MockMvc mvc, String path) throws Exception {
        return ProcessesHttp.json(mvc.perform(MockMvcRequestBuilders.get("/workflow/api/batch-operations" + path)
                .header("X-Api-Key", ProcessesHttp.API_KEY)).andReturn().getResponse());
    }

}

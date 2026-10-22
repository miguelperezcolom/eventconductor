package io.mateu.workflow.infra.out.persistence;

import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.data.domain.PageRequest;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

/** V58 on the migrated embedded schema, with real JPA mapping and the direct-child join. */
class IdeExecutionPersistenceTest {
    @Test void testMetadataSurvivesARepositoryReloadAndChildPagesKeepAllSourceExecutions() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:ide-"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1;MODE=PostgreSQL","sa","");
        PostgresTestDatabase.migrate(ds);
        try (var context = AnalyticsJpaTestContext.open(ds)) {
            var rows = context.getBean(ProcessEntityRepository.class);
            var jdbc = context.getBean(org.springframework.jdbc.core.JdbcTemplate.class);
            var parent = new ProcessEntity();
            parent.setId("parent"); parent.setStatus("RUNNING"); parent.setTestRun(true);
            parent.setTestRunRequestDigest("a".repeat(64)); parent.setWorkflowDefinitionId("draft");
            parent.setWorkflowDefinitionVersion(17); parent.setWorkflowDefinitionJson("{\"id\":\"draft\"}");
            rows.saveAndFlush(parent);
            for (String id : new String[]{"old","new","unrelated"})
                jdbc.update("insert into step_execution_entity (id,process_id,step_id,status) values (?,?,?,?)",
                    id,id.equals("unrelated")?"other-parent":"parent",id.equals("old")?"first":"second","COMPLETED");
            for (int i=0;i<5;i++) {
                var row = new ProcessEntity(); row.setId("child-"+i); row.setStatus("COMPLETED");
                row.setParentStepExecutionId(i==0?"old":i==4?"unrelated":"new"); row.setTestRun(true);
                row.setCreated(LocalDateTime.of(2026,10,20,9,i)); rows.saveAndFlush(row);
            }
            var repository = new ProcessDBRepository(rows,null,null,null,null,null,null,null,null,null,null,null,null);
            var restored = repository.findById("parent").orElseThrow();
            assertThat(restored.isTestRun()).isTrue();
            assertThat(restored.getTestRunRequestDigest()).isEqualTo("a".repeat(64));
            assertThat(restored.getWorkflowDefinitionVersion()).isEqualTo(17);
            var page = repository.findChildren("parent",java.util.Set.of("old","new"),null,1,2);
            assertThat(page.totalElements()).isEqualTo(4);
            assertThat(page.content()).extracting(Process::getId).containsExactly("child-2","child-3");
            var filtered = rows.findDirectChildren("parent","first",PageRequest.of(0,20));
            assertThat(filtered.getContent()).extracting(ProcessEntity::getId).containsExactly("child-0");
            assertThat(rows.findDirectChildren("parent","missing",PageRequest.of(0,20))).isEmpty();
            jdbc.update("insert into process_entity (id,status) values ('normal','RUNNING')");
            assertThat(repository.findById("normal").orElseThrow().isTestRun()).isFalse();
        }
    }
}

package io.mateu.workflow.infra.out.persistence;

import io.mateu.workflow.infra.in.ui.pages.process.childcruds.Error;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LogMessageEntityRepository extends JpaRepository<LogMessageEntity, String> {
    List<LogMessageEntity> findAllByProcessId(String processId);

    /**
     * The error lines tied to a step, of these processes — what the incidents view reads a failure's
     * message from. Driven by {@code idx_log_process}. The type is compared case-insensitively, as
     * {@code MessageType.isError} reads it.
     */
    @org.springframework.data.jpa.repository.Query("""
            select l.stepExecutionId as stepExecutionId, l.message as message, l.timestamp as timestamp
            from LogMessageEntity l
            where l.processId in :processIds
              and l.stepExecutionId is not null
              and lower(l.messageType) = 'error'
            """)
    List<ErrorLineView> findErrorLinesOfProcesses(
            @org.springframework.data.repository.query.Param("processIds") java.util.Collection<String> processIds);

    interface ErrorLineView {
        String getStepExecutionId();

        String getMessage();

        java.time.LocalDateTime getTimestamp();
    }
}

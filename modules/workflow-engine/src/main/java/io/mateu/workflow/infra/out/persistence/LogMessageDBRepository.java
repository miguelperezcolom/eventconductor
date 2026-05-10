package io.mateu.workflow.infra.out.persistence;

import io.mateu.workflow.application.out.LogMessageRepository;
import io.mateu.workflow.domain.aggregates.LogMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
@ConditionalOnProperty(name = "workflow.persistence", havingValue = "jpa", matchIfMissing = true)
@RequiredArgsConstructor
public class LogMessageDBRepository implements LogMessageRepository {

    final LogMessageEntityRepository logMessageEntityRepository;

    @Override
    public Optional<LogMessage> findById(String id) {
        return logMessageEntityRepository.findById(id)
                .map(this::map);
    }

    private LogMessage map(LogMessageEntity entity) {
        return new LogMessage(
                entity.getId(),
                entity.getTimestamp(),
                entity.getProcessId(),
                entity.getStepExecutionId(),
                entity.getMessageType(),
                entity.getMessage(),
                entity.getWorkerId()
        );
    }

    @Override
    public String save(LogMessage message) {
        logMessageEntityRepository.save(new LogMessageEntity(
                message.getId(),
                message.getTimestamp(),
                message.getProcessId(),
                message.getStepExecutionId(),
                message.getMessageType(),
                message.getMessage(),
                message.getWorkerId()
        ));
        return message.getId();
    }

    @Override
    public List<LogMessage> findAll() {
        return logMessageEntityRepository.findAll().stream().map(this::map).toList();
    }

    @Override
    public void deleteAllById(List<String> selectedIds) {
        logMessageEntityRepository.deleteAllById(selectedIds);
    }
}

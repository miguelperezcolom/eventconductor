package io.mateu.workflow.infra.in.ui.pages.process.childcruds.adapters;

import io.mateu.core.infra.declarative.orchestrators.crud.AutoListAdapter;
import io.mateu.uidl.data.Status;
import io.mateu.uidl.data.StatusType;
import io.mateu.uidl.interfaces.CrudRepository;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.infra.in.ui.pages.process.childcruds.Step;
import io.mateu.workflow.infra.out.persistence.StepExecutionEntityRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Scope;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

import static io.mateu.uidl.Humanizer.toUpperCaseFirst;


@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(name = "workflow.persistence", havingValue = "jpa")
@Service
@Scope("prototype")
@RequiredArgsConstructor
public class StepCrudAdapter extends AutoListAdapter<Step> {

    final StepExecutionEntityRepository repository;
    private String processId;

    public StepCrudAdapter withProcessId(String processId) {
        this.processId = processId;
        return this;
    }

    @Override
    public CrudRepository<Step> repository() {
        return new CrudRepository<Step>() {
            @Override
            public Optional<Step> findById(String id) {
                throw new UnsupportedOperationException();
            }

            @Override
            public String save(Step entity) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<Step> findAll() {
                return repository.findAllByProcessIdOrderByOrder(processId).stream()
                        .map(entity -> new Step(processId, entity.getId(), entity.getStepId(), map(entity.getStatus())))
                        .toList();
            }

            @Override
            public void deleteAllById(List<String> selectedIds) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private Status map(String rawStatus) {
        StepExecutionStatus status = StepExecutionStatus.valueOf(rawStatus);
        StatusType statusType = switch (status) {
            case CREATED -> StatusType.NONE;
            case PENDING -> StatusType.INFO;
            case RUNNING -> StatusType.WARNING;
            case COMPLETED -> StatusType.SUCCESS;
            case CANCELLED -> StatusType.DANGER;
            case ERROR, TIMEOUT -> StatusType.DANGER;
        };
        return new Status(statusType, toUpperCaseFirst(status.name()));
    }

}

package io.mateu.workflow.infra.in.ui.adapters;

import io.mateu.uidl.data.*;
import io.mateu.uidl.interfaces.CrudRepository;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.domain.aggregates.StepExecution;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.infra.in.ui.pages.steps.StepExecutionFilters;
import io.mateu.workflow.infra.in.ui.pages.steps.StepExecutionRow;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import static io.mateu.uidl.Humanizer.toUpperCaseFirst;

@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@Service
@RequiredArgsConstructor
public class StepExecutionsCrudAdapter {

    final StepExecutionRepository repository;
    final DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public ListingData<StepExecutionRow> search(String searchText, StepExecutionFilters filters, Pageable pageable, HttpRequest httpRequest) {
        // SearchActionHandler passes filters = null; the filter values only travel in the component state
        boolean onlyErrors = filters != null && Boolean.TRUE.equals(filters.onlyErrors())
                || filters == null && SimpleProcessCrudAdapter.stateFlag(httpRequest, "onlyErrors");
        List<StepExecutionRow> all = repository.findAll().stream()
                .filter(stepExecution -> !onlyErrors
                        || StepExecutionStatus.ERROR.equals(stepExecution.getStatus())
                        || StepExecutionStatus.TIMEOUT.equals(stepExecution.getStatus()))
                .filter(stepExecution -> searchText == null || searchText.isEmpty()
                        || searchableText(stepExecution).toLowerCase().contains(searchText.toLowerCase()))
                .sorted(Comparator.comparing(StepExecution::getStartedAt,
                        Comparator.nullsLast(Comparator.<LocalDateTime>reverseOrder())))
                .map(this::map)
                .toList();
        List<StepExecutionRow> page = all.stream()
                .skip((long) pageable.page() * pageable.size())
                .limit(pageable.size())
                .toList();
        return new ListingData<>(new Page<>(searchText, page.size(), pageable.page(), all.size(), page));
    }

    private String searchableText(StepExecution stepExecution) {
        return stepExecution.id() + " " + stepExecution.getProcessId() + " " + stepExecution.getStepId();
    }

    private StepExecutionRow map(StepExecution stepExecution) {
        return new StepExecutionRow(
                stepExecution.id(),
                stepExecution.getProcessId(),
                stepExecution.getStepId(),
                mapStatus(stepExecution.getStatus()),
                stepExecution.getStartedAt() != null ? stepExecution.getStartedAt().format(dtf) : null,
                stepExecution.getAttemptCount());
    }

    private Status mapStatus(StepExecutionStatus status) {
        StatusType statusType = switch (status) {
            case CREATED -> StatusType.NONE;
            case PENDING -> StatusType.INFO;
            case RUNNING -> StatusType.WARNING;
            case COMPLETED -> StatusType.SUCCESS;
            case CANCELLED, ERROR, TIMEOUT -> StatusType.DANGER;
        };
        return new Status(statusType, toUpperCaseFirst(status.name()));
    }

    public CrudRepository<StepExecutionRow> repository() {
        return new CrudRepository<StepExecutionRow>() {
            @Override
            public Optional<StepExecutionRow> findById(String id) {
                return repository.findById(id).map(StepExecutionsCrudAdapter.this::map);
            }

            @Override
            public String save(StepExecutionRow entity) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<StepExecutionRow> findAll() {
                return repository.findAll().stream().map(StepExecutionsCrudAdapter.this::map).toList();
            }

            @Override
            public void deleteAllById(List<String> selectedIds) {
                throw new UnsupportedOperationException();
            }
        };
    }
}

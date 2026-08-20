package io.mateu.workflow.infra.in.ui.adapters;

import io.mateu.uidl.data.*;
import io.mateu.uidl.interfaces.CrudStore;
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
        // The page SIZE is the one asked for, not the rows this page happens to carry: past the
        // end that is 0, and the pager divides by it ("Page 3423 of Infinity"). A page beyond the
        // end serves the last real one, so a stale deep link recovers instead of an empty grid.
        int size = pageable.size() > 0 ? pageable.size() : all.size();
        int lastPage = size > 0 ? Math.max(0, (all.size() - 1) / size) : 0;
        int pageNumber = Math.min(Math.max(pageable.page(), 0), lastPage);
        List<StepExecutionRow> page = all.stream()
                .skip((long) pageNumber * size)
                .limit(size)
                .toList();
        return new ListingData<>(new Page<>(searchText, size, pageNumber, all.size(), page));
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
            case RUNNING, AWAITING_RETRY -> StatusType.WARNING;
            case COMPLETED -> StatusType.SUCCESS;
            case CANCELLED, ERROR, TIMEOUT -> StatusType.DANGER;
        };
        return new Status(statusType, toUpperCaseFirst(status.name()));
    }

    public CrudStore<StepExecutionRow> repository() {
        return new CrudStore<StepExecutionRow>() {
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

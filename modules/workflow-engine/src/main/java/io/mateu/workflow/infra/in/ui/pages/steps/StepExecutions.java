package io.mateu.workflow.infra.in.ui.pages.steps;

import io.mateu.core.infra.declarative.orchestrators.crud.FilteredAutoCrud;
import io.mateu.uidl.annotations.ListToolbarButton;
import io.mateu.uidl.annotations.ReadOnly;
import io.mateu.uidl.data.ListingData;
import io.mateu.uidl.data.Pageable;
import io.mateu.uidl.interfaces.CrudStore;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.workflow.dtos.events.integration.RetryStepExecutionRequested;
import io.mateu.workflow.application.usecases.stepexecution.retry.RetryStepExecutionCommand;
import io.mateu.workflow.application.usecases.stepexecution.retry.RetryStepExecutionUseCase;
import io.mateu.workflow.infra.in.ui.adapters.StepExecutionsCrudAdapter;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.util.List;

@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@Service
@Scope("prototype")
@RequiredArgsConstructor
@ReadOnly
public class StepExecutions extends FilteredAutoCrud<StepExecutionFilters, StepExecutionRow> {

    final io.mateu.workflow.application.out.UpstreamEventPublisher upstreamEventPublisher;
    final StepExecutionsCrudAdapter stepExecutionsCrudAdapter;
    final RetryStepExecutionUseCase retryStepExecutionUseCase;

    @Override
    public Class filtersClass() {
        return StepExecutionFilters.class;
    }

    @Override
    public CrudStore<StepExecutionRow> store() {
        return stepExecutionsCrudAdapter.repository();
    }

    @Override
    public ListingData<StepExecutionRow> fetchRows(String searchText, StepExecutionFilters filters, Pageable pageable, HttpRequest httpRequest) {
        return stepExecutionsCrudAdapter.search(searchText, filters, pageable, httpRequest);
    }

    @Override
    public String title() {
        return "Steps";
    }

    @ListToolbarButton(rowsSelectedRequired = true)
    public void retry(List<StepExecutionRow> selectedRows) {
        // Requested, not performed here. The process id comes along so the event reaches the
        // pod that owns it — a step id alone does not say which partition that is.
        selectedRows.forEach(row -> upstreamEventPublisher.publish(
                new RetryStepExecutionRequested(row.id(), row.processId())));
    }
}

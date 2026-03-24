package io.mateu.workflow.controlplaneservice.infra.in.ui.pages.environment;

import io.mateu.uidl.data.ListingData;
import io.mateu.uidl.data.NoFilters;
import io.mateu.uidl.data.Pageable;
import io.mateu.uidl.interfaces.CrudAdapter;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.workflow.controlplaneservice.application.out.EnvironmentRepository;
import io.mateu.workflow.controlplaneservice.application.query.EnvironmentQueryService;
import io.mateu.workflow.controlplaneservice.application.query.dto.EnvironmentRow;
import io.mateu.workflow.controlplaneservice.application.usecases.environment.delete.DeleteEnvironmentCommand;
import io.mateu.workflow.controlplaneservice.application.usecases.environment.delete.DeleteEnvironmentUseCase;
import io.mateu.workflow.controlplaneservice.domain.aggregates.environment.vo.EnvironmentId;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@Scope("prototype")
@RequiredArgsConstructor
public class EnvironmentCrudAdapter implements CrudAdapter<
EnvironmentViewModel,
EnvironmentViewModel,
EnvironmentViewModel,
NoFilters,
EnvironmentRow,
String
> {

final EnvironmentViewModel viewModel;
final DeleteEnvironmentUseCase deleteEnvironmentUseCase;
final EnvironmentQueryService queryService;

@Override
public ListingData<EnvironmentRow> search(String searchText,
    NoFilters filters,
    Pageable pageable) {
    return queryService.findAll(searchText, filters, pageable);
    }

    @Override
    public void deleteAllById(List<String> selectedIds) {
        deleteEnvironmentUseCase.handle(new DeleteEnvironmentCommand(selectedIds));
        }

        @Override
        public EnvironmentViewModel getView(String id) {
        return viewModel.load(queryService
        .getById(id)
        .orElseThrow());
        }

        @Override
        public EnvironmentViewModel getEditor(String id) {
        return viewModel.load(queryService
        .getById(id)
        .orElseThrow());
        }

        @Override
        public EnvironmentViewModel getCreationForm(HttpRequest httpRequest) {
        return viewModel;
        }
        }

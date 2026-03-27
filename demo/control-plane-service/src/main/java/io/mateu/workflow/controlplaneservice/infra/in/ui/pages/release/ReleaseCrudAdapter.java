package io.mateu.workflow.controlplaneservice.infra.in.ui.pages.release;

import io.mateu.uidl.data.ListingData;
import io.mateu.uidl.data.NoFilters;
import io.mateu.uidl.data.Pageable;
import io.mateu.uidl.interfaces.CrudAdapter;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.workflow.controlplaneservice.application.query.ReleaseQueryService;
import io.mateu.workflow.controlplaneservice.application.query.dto.ReleaseRow;
import io.mateu.workflow.controlplaneservice.application.usecases.release.delete.DeleteReleaseCommand;
import io.mateu.workflow.controlplaneservice.application.usecases.release.delete.DeleteReleaseUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@Scope("prototype")
@RequiredArgsConstructor
public class ReleaseCrudAdapter implements CrudAdapter<
        ReleaseViewModel,
        ReleaseViewModel,
        ReleaseViewModel,
        NoFilters,
        ReleaseRow,
        String
        > {

    final ReleaseViewModel viewModel;
    final DeleteReleaseUseCase deleteReleaseUseCase;
    final ReleaseQueryService queryService;

    @Override
    public ListingData<ReleaseRow> search(String searchText,
                                          NoFilters filters,
                                          Pageable pageable) {
        return queryService.findAll(searchText, filters, pageable);
    }

    @Override
    public void deleteAllById(List<String> selectedIds) {
        deleteReleaseUseCase.handle(new DeleteReleaseCommand(selectedIds));
    }

    @Override
    public ReleaseViewModel getView(String id) {
        return viewModel.load(queryService
                .getById(id)
                .orElseThrow());
    }

    @Override
    public ReleaseViewModel getEditor(String id) {
        return viewModel.load(queryService
                .getById(id)
                .orElseThrow());
    }

    @Override
    public ReleaseViewModel getCreationForm(HttpRequest httpRequest) {
        return viewModel;
    }
}

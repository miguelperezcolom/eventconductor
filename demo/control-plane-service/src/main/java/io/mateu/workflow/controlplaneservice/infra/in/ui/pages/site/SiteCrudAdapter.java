package io.mateu.workflow.controlplaneservice.infra.in.ui.pages.site;

import io.mateu.uidl.data.ListingData;
import io.mateu.uidl.data.NoFilters;
import io.mateu.uidl.data.Pageable;
import io.mateu.uidl.interfaces.CrudAdapter;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.workflow.controlplaneservice.application.query.SiteQueryService;
import io.mateu.workflow.controlplaneservice.application.query.dto.SiteRow;
import io.mateu.workflow.controlplaneservice.application.usecases.site.delete.DeleteSiteCommand;
import io.mateu.workflow.controlplaneservice.application.usecases.site.delete.DeleteSiteUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@Scope("prototype")
@RequiredArgsConstructor
public class SiteCrudAdapter implements CrudAdapter<
        SiteViewModel,
        SiteViewModel,
        SiteViewModel,
        NoFilters,
        SiteRow,
        String
        > {

    final SiteViewModel viewModel;
    final DeleteSiteUseCase deleteSiteUseCase;
    final SiteQueryService queryService;

    @Override
    public ListingData<SiteRow> search(String searchText,
                                       NoFilters filters,
                                       Pageable pageable) {
        return queryService.findAll(searchText, filters, pageable);
    }

    @Override
    public void deleteAllById(List<String> selectedIds) {
        deleteSiteUseCase.handle(new DeleteSiteCommand(selectedIds));
    }

    @Override
    public SiteViewModel getView(String id) {
        return viewModel.load(queryService
                .getById(id)
                .orElseThrow());
    }

    @Override
    public SiteViewModel getEditor(String id) {
        return viewModel.load(queryService
                .getById(id)
                .orElseThrow());
    }

    @Override
    public SiteViewModel getCreationForm(HttpRequest httpRequest) {
        return viewModel;
    }
}

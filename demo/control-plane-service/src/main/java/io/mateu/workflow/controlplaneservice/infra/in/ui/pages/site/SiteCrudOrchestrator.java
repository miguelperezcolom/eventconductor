package io.mateu.workflow.controlplaneservice.infra.in.ui.pages.site;

import io.mateu.core.infra.declarative.orchestrators.crud.Crud;
import io.mateu.uidl.annotations.Title;
import io.mateu.uidl.annotations.ViewToolbarButton;
import io.mateu.uidl.data.ListingData;
import io.mateu.uidl.data.NoFilters;
import io.mateu.uidl.data.SearchRequest;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.workflow.controlplaneservice.application.query.SiteQueryService;
import io.mateu.workflow.controlplaneservice.application.query.dto.SiteRow;
import io.mateu.workflow.controlplaneservice.application.usecases.scrape.DownloadAssetsUseCase;
import io.mateu.workflow.controlplaneservice.application.usecases.site.delete.DeleteSiteCommand;
import io.mateu.workflow.controlplaneservice.application.usecases.site.delete.DeleteSiteUseCase;
import io.mateu.workflow.controlplaneservice.application.usecases.site.scrape.AskForScrapeCommand;
import io.mateu.workflow.controlplaneservice.application.usecases.site.scrape.AskForScrapeUseCase;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Title("Sites")
public class SiteCrudOrchestrator extends Crud<
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
    final AskForScrapeUseCase askForScrapeUseCase;
    final DownloadAssetsUseCase downloadAssetsUseCase;

    @Override
    public ListingData<SiteRow> search(SearchRequest request, HttpRequest httpRequest) {
        return queryService.findAll(request.searchText(), filters(request), request.pageable());
    }

    @Override
    public SiteViewModel view(String id, HttpRequest httpRequest) {
        return viewModel.load(queryService.getById(id).orElseThrow());
    }

    @Override
    public SiteViewModel edit(String id, HttpRequest httpRequest) {
        return viewModel.load(queryService.getById(id).orElseThrow());
    }

    @Override
    public SiteViewModel creationForm(HttpRequest httpRequest) {
        return viewModel;
    }

    @Override
    public String save(HttpRequest httpRequest) {
        var editor = httpRequest.getComponentState(SiteViewModel.class);
        editor.save(httpRequest);
        return editor.id();
    }

    @Override
    public String create(HttpRequest httpRequest) {
        var form = httpRequest.getComponentState(SiteViewModel.class);
        return form.create(httpRequest);
    }

    @Override
    public void deleteAllById(List<String> selectedIds, HttpRequest httpRequest) {
        deleteSiteUseCase.handle(new DeleteSiteCommand(selectedIds));
    }

    @SneakyThrows
    @ViewToolbarButton
    public Object scrape(SiteViewModel site, HttpRequest httpRequest) {
        var processBusinessKey = UUID.randomUUID().toString();
        askForScrapeUseCase.handle(new AskForScrapeCommand(site.id, processBusinessKey));
        return URI.create("/workflow/processes/" + processBusinessKey);
    }

}

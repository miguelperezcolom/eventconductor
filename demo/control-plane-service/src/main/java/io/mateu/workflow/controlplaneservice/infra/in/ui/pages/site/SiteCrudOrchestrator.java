package io.mateu.workflow.controlplaneservice.infra.in.ui.pages.site;

import io.mateu.core.infra.declarative.CrudOrchestrator;
import io.mateu.uidl.annotations.Title;
import io.mateu.uidl.annotations.ViewToolbarButton;
import io.mateu.uidl.data.NoFilters;
import io.mateu.uidl.interfaces.CrudAdapter;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.workflow.controlplaneservice.application.query.dto.SiteRow;
import io.mateu.workflow.controlplaneservice.application.usecases.route.downloadassets.DownloadAssetsUseCase;
import io.mateu.workflow.controlplaneservice.application.usecases.site.scrape.AskForScrapeCommand;
import io.mateu.workflow.controlplaneservice.application.usecases.site.scrape.AskForScrapeUseCase;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Title("Sites")
public class SiteCrudOrchestrator extends CrudOrchestrator<
        SiteViewModel,
        SiteViewModel,
        SiteViewModel,
        NoFilters,
        SiteRow,
        String
        > {

    final SiteCrudAdapter adapter;
    final AskForScrapeUseCase askForScrapeUseCase;
    final DownloadAssetsUseCase downloadAssetsUseCase;

    @Override
    public CrudAdapter<SiteViewModel,
            SiteViewModel, SiteViewModel,
            NoFilters, SiteRow, String> adapter() {
        return adapter;
    }

    @Override
    public String toId(String s) {
        return s;
    }

    @SneakyThrows
    @ViewToolbarButton
    public Object scrape(SiteViewModel site, HttpRequest httpRequest) {
        var processBusinessKey = UUID.randomUUID().toString();
        askForScrapeUseCase.handle(new AskForScrapeCommand(site.id, processBusinessKey));
        return URI.create("/workflow/processes/" + processBusinessKey);
    }

}

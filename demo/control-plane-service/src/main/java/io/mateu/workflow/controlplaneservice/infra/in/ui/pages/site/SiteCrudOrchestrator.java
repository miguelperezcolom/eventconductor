package io.mateu.workflow.controlplaneservice.infra.in.ui.pages.site;

import io.mateu.core.infra.declarative.CrudOrchestrator;
import io.mateu.uidl.annotations.Title;
import io.mateu.uidl.annotations.ViewToolbarButton;
import io.mateu.uidl.data.NoFilters;
import io.mateu.uidl.data.Status;
import io.mateu.uidl.data.StatusType;
import io.mateu.uidl.interfaces.CrudAdapter;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.workflow.controlplaneservice.application.query.dto.SiteRow;
import io.mateu.workflow.controlplaneservice.application.usecases.route.downloadassets.DownloadAssetsCommand;
import io.mateu.workflow.controlplaneservice.application.usecases.route.downloadassets.DownloadAssetsUseCase;
import io.mateu.workflow.controlplaneservice.application.usecases.site.scrape.AskForScrapeCommand;
import io.mateu.workflow.controlplaneservice.application.usecases.site.scrape.AskForScrapeUseCase;
import io.mateu.workflow.controlplaneservice.infra.in.ui.pages.deployment.process.Error;
import io.mateu.workflow.controlplaneservice.infra.in.ui.pages.deployment.process.Message;
import io.mateu.workflow.controlplaneservice.infra.in.ui.pages.deployment.process.Resource;
import io.mateu.workflow.controlplaneservice.infra.in.ui.pages.deployment.process.Step;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
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

    Status status;
    List<Step> steps = new ArrayList<>();
    List<Message> messages = new ArrayList<>();
    List<Error> errors = new ArrayList<>();
    List<Resource> resources = new ArrayList<>();

    final ScrapeProcessViewModel scrapeProcessViewModel;
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


    @SneakyThrows
    //@ViewToolbarButton
    public Object oldScrape(SiteViewModel site, HttpRequest httpRequest) {
        if (status == null || status.type().equals(StatusType.SUCCESS)  || status.type().equals(StatusType.DANGER)  || status.type().equals(StatusType.NONE)) {

            status = new Status(StatusType.WARNING, "Running");

            scrapeProcessViewModel.reset();
            steps.clear();
            messages.clear();
            errors.clear();
            resources.clear();
            steps.add(new Step("x", "0", "Create urls", new Status(StatusType.INFO, "Pending")));
            steps.add(new Step("x", "1", "Download", new Status(StatusType.INFO, "Pending")));
            new Thread(() -> {
                try {
                    steps.set(0, new Step("x", "0", "Create urls", new Status(StatusType.WARNING, "Running")));
                    askForScrapeUseCase.handle(new AskForScrapeCommand(site.id, ""));
                    steps.set(0, new Step("x", "0", "Create urls", new Status(StatusType.SUCCESS, "Complete")));
                    steps.set(1, new Step("x", "1", "Download", new Status(StatusType.WARNING, "Running")));
                    downloadAssetsUseCase.handle(new DownloadAssetsCommand(site.id));
                    steps.set(1, new Step("x", "1", "Download", new Status(StatusType.SUCCESS, "Complete")));

                    status = new Status(StatusType.SUCCESS, "Complete");
                } catch (Throwable e) {
                    failed();
                    status = new Status(StatusType.DANGER, "Error");
                }
            }).start();
        }
        return scrapeProcessViewModel;
    }

    void failed() {
        for (var i = 0; i < steps.size(); i++) {
            var step = steps.get(i);
            if (step.status().type().equals(StatusType.INFO)) {
                steps.set(i, new Step(step.processId(), step.id(), step.name(), new Status(StatusType.NONE, "Cancelled")));
            }
        }
    }
}

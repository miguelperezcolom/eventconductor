package io.mateu.workflow.controlplaneservice.infra.in.ui.pages.deployment;

import io.mateu.uidl.annotations.Title;
import io.mateu.uidl.annotations.Toolbar;
import io.mateu.uidl.annotations.Trigger;
import io.mateu.uidl.annotations.TriggerType;
import io.mateu.uidl.data.*;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.uidl.interfaces.ListingBackend;
import io.mateu.workflow.controlplaneservice.application.out.ReleaseRepository;
import io.mateu.workflow.controlplaneservice.application.query.DeploymentQueryService;
import io.mateu.workflow.controlplaneservice.application.usecases.deploy.DeployCommand;
import io.mateu.workflow.controlplaneservice.application.usecases.deploy.DeployUseCase;
import io.mateu.workflow.controlplaneservice.domain.aggregates.release.Release;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Title("Deployer")
@Service
@Scope("prototype")
@RequiredArgsConstructor
@Slf4j
@Trigger(type = TriggerType.OnLoad, actionId = "search")
@Trigger(type = TriggerType.OnSuccess, calledActionId = "deployRelease", actionId = "search")
public class Deployer implements ListingBackend<NoFilters, DeploymentRow> {

    final DeployReleaseForm deployReleaseForm;
    final DeploymentQueryService queryService;
    final ReleaseRepository releaseRepository;

    @Override
    public ListingData<DeploymentRow> search(String searchText, NoFilters filters, Pageable pageable, HttpRequest httpRequest) {
        var found = queryService.findAll(searchText, filters, pageable);
        Map<Long, Release> releases = releaseRepository.findAll().stream()
                .collect(Collectors.toMap(
                        r -> r.getId().id(),
                        Function.identity(),
                        (r1, r2) -> r1
                ));
        return ListingData.<DeploymentRow>builder()
                .page(Page.<DeploymentRow>builder()
                        .searchSignature(found.page().searchSignature())
                        .totalElements(found.page().totalElements())
                        .pageSize(found.page().pageSize())
                        .pageNumber(found.page().pageNumber())
                        .content(found.page().content().stream()
                                .map(dto -> new DeploymentRow(
                                        dto.id(),
                                        dto.route(),
                                        dto.country(),
                                        toStatus(releases.get(dto.releaseId()))
                                ))
                                .toList())
                        .build())
                .build();
    }

    private Status toStatus(Release release) {
        if (release == null) return new Status(StatusType.NONE, "Not released yet");
        return new Status(switch (release.getStatus()) {
            case Archived -> StatusType.NONE;
            case Blue -> StatusType.INFO;
            case Green -> StatusType.SUCCESS;
            default -> StatusType.WARNING;
        }, release.getName().name());
    }

    @Toolbar
    public DeployReleaseForm deployRelease(List<DeploymentRow> selectedRows) {
        log.info("deploy release {}", selectedRows);
        return deployReleaseForm.withRouteIds(selectedRows.stream().map(DeploymentRow::id).toList());
    }
}

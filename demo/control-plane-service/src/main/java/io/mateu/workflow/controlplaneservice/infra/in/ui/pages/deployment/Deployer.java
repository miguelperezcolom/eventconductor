package io.mateu.workflow.controlplaneservice.infra.in.ui.pages.deployment;

import io.mateu.uidl.annotations.Title;
import io.mateu.uidl.annotations.Toolbar;
import io.mateu.uidl.data.*;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.uidl.interfaces.ListingBackend;
import io.mateu.workflow.controlplaneservice.application.query.DeploymentQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.util.List;

@Title("Deployments")
@Service
@Scope("prototype")
@RequiredArgsConstructor
public class Deployer implements ListingBackend<NoFilters, DeploymentRow> {

    final DeploymentQueryService queryService;

    @Override
    public ListingData<DeploymentRow> search(String searchText, NoFilters filters, Pageable pageable, HttpRequest httpRequest) {
        var found = queryService.findAll(searchText, filters, pageable);
        return ListingData.<DeploymentRow>builder()
                .page(Page.<DeploymentRow>builder()
                        .searchSignature(found.page().searchSignature())
                        .totalElements(found.page().totalElements())
                        .pageSize(found.page().pageSize())
                        .pageNumber(found.page().pageNumber())
                        .content(found.page().content().stream()
                                .map(dto -> new DeploymentRow(
                                        dto.id(),
                                        dto.site(),
                                        dto.country(),
                                        dto.release()
                                ))
                                .toList())
                        .build())
                .build();
    }

    @Toolbar
    public void deployRelease() {}

    @Toolbar
    public void deployBlue(HttpRequest httpRequest) {}

    @Toolbar
    public void deployGreen(HttpRequest httpRequest) {}
}

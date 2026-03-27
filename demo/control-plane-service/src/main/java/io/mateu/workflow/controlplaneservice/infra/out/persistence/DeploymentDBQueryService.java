package io.mateu.workflow.controlplaneservice.infra.out.persistence;

import io.mateu.uidl.data.ListingData;
import io.mateu.uidl.data.Page;
import io.mateu.uidl.data.Pageable;
import io.mateu.workflow.controlplaneservice.application.query.ChangeQueryService;
import io.mateu.workflow.controlplaneservice.application.query.DeploymentQueryService;
import io.mateu.workflow.controlplaneservice.application.query.dto.ChangeDto;
import io.mateu.workflow.controlplaneservice.application.query.dto.ChangeStatus;
import io.mateu.workflow.controlplaneservice.application.query.dto.DeploymentDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DeploymentDBQueryService implements DeploymentQueryService {

    final RouteEntityRepository repository;

    @Override
    public ListingData<DeploymentDto> findAll(String searchText, Object filters, Pageable pageable) {
        var all = repository.findAll();
        return ListingData.<DeploymentDto>builder()
                .page(Page.<DeploymentDto>builder()
                        .pageNumber(0)
                        .pageSize(all.size())
                        .totalElements(all.size())
                        .searchSignature(searchText)
                        .content(all.stream()
                                .map(page -> new DeploymentDto(
                                        page.id.toString(),
                                        page.name,
                                        page.countryCode,
                                        "202603271530"
                                ))
                                .toList())
                        .build())
                .build();
    }
}

package io.mateu.workflow.controlplaneservice.infra.out.persistence;

import io.mateu.uidl.data.ListingData;
import io.mateu.uidl.data.Pageable;
import io.mateu.uidl.data.Page;
import io.mateu.workflow.controlplaneservice.application.query.ChangeQueryService;
import io.mateu.workflow.controlplaneservice.application.query.dto.ChangeDto;
import io.mateu.workflow.controlplaneservice.application.query.dto.ChangeStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ChangeDBQueryService implements ChangeQueryService {

    final RouteEntityRepository repository;

    @Override
    public ListingData<ChangeDto> findAll(String searchText, Object filters, Pageable pageable) {
        var all = repository.findAll();
        return ListingData.<ChangeDto>builder()
                .page(Page.<ChangeDto>builder()
                        .pageNumber(0)
                        .pageSize(all.size())
                        .totalElements(all.size())
                        .searchSignature(searchText)
                        .content(all.stream()
                                .map(page -> new ChangeDto(
                                        page.id.toString(),
                                        page.name,
                                        page.countryCode,
                                        page.languageCode,
                                        ChangeStatus.Changed
                                ))
                                .toList())
                        .build())
                .build();
    }
}

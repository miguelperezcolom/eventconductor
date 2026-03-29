package io.mateu.workflow.controlplaneservice.infra.out.persistence;

import io.mateu.uidl.data.ColumnAction;
import io.mateu.uidl.data.ColumnActionGroup;
import io.mateu.uidl.data.ListingData;
import io.mateu.uidl.data.Page;
import io.mateu.uidl.data.Pageable;
import io.mateu.uidl.data.Status;
import io.mateu.uidl.data.StatusType;
import io.mateu.workflow.controlplaneservice.application.query.ReleaseQueryService;
import io.mateu.workflow.controlplaneservice.application.query.dto.ReleaseDto;
import io.mateu.workflow.controlplaneservice.application.query.dto.ReleaseRow;
import io.mateu.workflow.controlplaneservice.domain.aggregates.release.vo.ReleaseStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;

import static io.mateu.core.infra.JsonSerializer.listFromJson;


@Service
@RequiredArgsConstructor
public class ReleaseDBQueryService implements ReleaseQueryService {

    final ReleaseEntityRepository repository;

    private ReleaseRow toDomain(ReleaseEntity entity) {
        return new ReleaseRow(
                entity.id.toString(),
                entity.name,
                toStatus(entity.status),
                new ColumnActionGroup(new ColumnAction[]{
                        new ColumnAction("action-on-row-setAsBlue", "Set as blue"),
                        new ColumnAction("action-on-row-setAsGreen", "Set as green")
                })
        );
    }

    private Status toStatus(String rawStatus) {
        var status = ReleaseStatus.valueOf(rawStatus);
        return new Status(switch (status) {
            case New -> StatusType.WARNING;
            case Blue -> StatusType.INFO;
            case Archived -> StatusType.NONE;
            case Green -> StatusType.SUCCESS;
        }, status.name());
    }

    @Override
    public String getLabel(String id) {
        return repository.findById(Long.valueOf(id)).map(ReleaseEntity::getName).orElse("Unknown");
    }

    @Override
    public Optional<ReleaseDto> getById(String id) {
        return repository.findById(Long.valueOf(id)).map(this::toDto);
    }

    private ReleaseDto toDto(ReleaseEntity entity) {
        return new ReleaseDto(
                entity.id.toString(),
                entity.name,
                entity.userId,
                entity.date,
                entity.environmentId,
                entity.siteId
        );
    }

    @Override
    public ListingData<ReleaseRow> findAll(String searchText,
                                           Object filters, Pageable pageable) {
        var page = repository.findAllByNameContainingIgnoreCaseOrderByNameDesc(searchText, org.springframework.data.domain.Pageable
                .ofSize(pageable.size())
                .withPage(pageable.page())
        );
        return new ListingData(new Page(searchText, page.getSize(), page.getNumber(), page.getTotalElements(),
                page.getContent().stream().map(this::toDomain).toList()));
    }

}
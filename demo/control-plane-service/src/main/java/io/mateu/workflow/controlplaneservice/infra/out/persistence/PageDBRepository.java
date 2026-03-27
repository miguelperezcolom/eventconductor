package io.mateu.workflow.controlplaneservice.infra.out.persistence;

import io.mateu.workflow.controlplaneservice.application.out.PageRepository;
import io.mateu.workflow.controlplaneservice.domain.aggregates.page.Page;
import io.mateu.workflow.controlplaneservice.domain.aggregates.page.vo.PageId;
import io.mateu.workflow.controlplaneservice.domain.aggregates.page.vo.PageJsonLd;
import io.mateu.workflow.controlplaneservice.domain.aggregates.page.vo.PageName;
import io.mateu.workflow.controlplaneservice.domain.aggregates.page.vo.PagePath;
import io.mateu.workflow.controlplaneservice.domain.aggregates.site.vo.SiteId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class PageDBRepository implements PageRepository {

    final PageEntityRepository repository;

    @Override
    public Optional<Page> findById(PageId id) {
        return repository.findById(id.id()).map(this::toDomain);
    }

    private Page toDomain(PageEntity entity) {
        return new Page(
                new PageId(entity.id),
                new SiteId(entity.siteId),
                new PageName(entity.name),
                new PagePath(entity.path),
                new PageJsonLd(entity.jsonLd)
        );
    }

    private PageEntity toEntity(Page page) {
        return new PageEntity(
                page.getId() != null ? page.getId().id() : null,
                page.getSiteId().id(),
                page.getName().name(),
                page.getPath().path(),
                page.getJsonLd().json()
        );
    }

    @Override
    public PageId save(Page page) {
        return new PageId(repository.save(toEntity(page)).id);
    }

    @Override
    public void deleteAllById(List<PageId> selectedIds) {
        repository.deleteAllById(selectedIds.stream().map(PageId::id).toList());
    }
}

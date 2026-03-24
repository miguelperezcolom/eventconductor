package io.mateu.workflow.controlplaneservice.infra.in.ui.pages.page;

import io.mateu.uidl.data.ListingData;
import io.mateu.uidl.data.NoFilters;
import io.mateu.uidl.data.Pageable;
import io.mateu.uidl.interfaces.CrudAdapter;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.workflow.controlplaneservice.application.out.PageRepository;
import io.mateu.workflow.controlplaneservice.application.query.PageQueryService;
import io.mateu.workflow.controlplaneservice.application.query.dto.PageRow;
import io.mateu.workflow.controlplaneservice.application.usecases.page.delete.DeletePageCommand;
import io.mateu.workflow.controlplaneservice.application.usecases.page.delete.DeletePageUseCase;
import io.mateu.workflow.controlplaneservice.domain.aggregates.page.vo.PageId;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@Scope("prototype")
@RequiredArgsConstructor
public class PageCrudAdapter implements CrudAdapter<
PageViewModel,
PageViewModel,
PageViewModel,
NoFilters,
PageRow,
String
> {

final PageViewModel viewModel;
final DeletePageUseCase deletePageUseCase;
final PageQueryService queryService;

@Override
public ListingData<PageRow> search(String searchText,
    NoFilters filters,
    Pageable pageable) {
    return queryService.findAll(searchText, filters, pageable);
    }

    @Override
    public void deleteAllById(List<String> selectedIds) {
        deletePageUseCase.handle(new DeletePageCommand(selectedIds));
        }

        @Override
        public PageViewModel getView(String id) {
        return viewModel.load(queryService
        .getById(id)
        .orElseThrow());
        }

        @Override
        public PageViewModel getEditor(String id) {
        return viewModel.load(queryService
        .getById(id)
        .orElseThrow());
        }

        @Override
        public PageViewModel getCreationForm(HttpRequest httpRequest) {
        return viewModel;
        }
        }

package io.mateu.workflow.contentservice.infra.in.ui.pages.content;

import io.mateu.uidl.data.ListingData;
import io.mateu.uidl.data.NoFilters;
import io.mateu.uidl.data.Pageable;
import io.mateu.uidl.interfaces.CrudAdapter;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.workflow.contentservice.application.out.ContentRepository;
import io.mateu.workflow.contentservice.application.query.ContentQueryService;
import io.mateu.workflow.contentservice.application.query.dto.ContentRow;
import io.mateu.workflow.contentservice.application.usecases.content.delete.DeleteContentCommand;
import io.mateu.workflow.contentservice.application.usecases.content.delete.DeleteContentUseCase;
import io.mateu.workflow.contentservice.domain.aggregates.content.vo.ContentId;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@Scope("prototype")
@RequiredArgsConstructor
public class ContentCrudAdapter implements CrudAdapter<
ContentViewModel,
ContentViewModel,
ContentViewModel,
NoFilters,
ContentRow,
String
> {

final ContentViewModel viewModel;
final DeleteContentUseCase deleteContentUseCase;
final ContentQueryService queryService;

@Override
public ListingData<ContentRow> search(String searchText,
    NoFilters filters,
    Pageable pageable, HttpRequest httpRequest) {
    return queryService.findAll(searchText, filters, pageable);
    }

    @Override
    public void deleteAllById(List<String> selectedIds, HttpRequest httpRequest) {
        deleteContentUseCase.handle(new DeleteContentCommand(selectedIds));
        }

        @Override
        public ContentViewModel getView(String id, HttpRequest httpRequest) {
        return viewModel.load(queryService
        .getById(id)
        .orElseThrow());
        }

        @Override
        public ContentViewModel getEditor(String id, HttpRequest httpRequest) {
        return viewModel.load(queryService
        .getById(id)
        .orElseThrow());
        }

        @Override
        public ContentViewModel getCreationForm(HttpRequest httpRequest) {
        return viewModel;
        }
        }

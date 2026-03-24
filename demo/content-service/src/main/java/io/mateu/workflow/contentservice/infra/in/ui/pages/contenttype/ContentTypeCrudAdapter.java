package io.mateu.workflow.contentservice.infra.in.ui.pages.contenttype;

import io.mateu.uidl.data.ListingData;
import io.mateu.uidl.data.NoFilters;
import io.mateu.uidl.data.Pageable;
import io.mateu.uidl.interfaces.CrudAdapter;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.workflow.contentservice.application.out.ContentTypeRepository;
import io.mateu.workflow.contentservice.application.query.ContentTypeQueryService;
import io.mateu.workflow.contentservice.application.query.dto.ContentTypeRow;
import io.mateu.workflow.contentservice.application.usecases.contenttype.delete.DeleteContentTypeCommand;
import io.mateu.workflow.contentservice.application.usecases.contenttype.delete.DeleteContentTypeUseCase;
import io.mateu.workflow.contentservice.domain.aggregates.contenttype.vo.ContentTypeId;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@Scope("prototype")
@RequiredArgsConstructor
public class ContentTypeCrudAdapter implements CrudAdapter<
ContentTypeViewModel,
ContentTypeViewModel,
ContentTypeViewModel,
NoFilters,
ContentTypeRow,
String
> {

final ContentTypeViewModel viewModel;
final DeleteContentTypeUseCase deleteContentTypeUseCase;
final ContentTypeQueryService queryService;

@Override
public ListingData<ContentTypeRow> search(String searchText,
    NoFilters filters,
    Pageable pageable) {
    return queryService.findAll(searchText, filters, pageable);
    }

    @Override
    public void deleteAllById(List<String> selectedIds) {
        deleteContentTypeUseCase.handle(new DeleteContentTypeCommand(selectedIds));
        }

        @Override
        public ContentTypeViewModel getView(String id) {
        return viewModel.load(queryService
        .getById(id)
        .orElseThrow());
        }

        @Override
        public ContentTypeViewModel getEditor(String id) {
        return viewModel.load(queryService
        .getById(id)
        .orElseThrow());
        }

        @Override
        public ContentTypeViewModel getCreationForm(HttpRequest httpRequest) {
        return viewModel;
        }
        }

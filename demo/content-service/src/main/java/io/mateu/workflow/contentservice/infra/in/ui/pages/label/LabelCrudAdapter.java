package io.mateu.workflow.contentservice.infra.in.ui.pages.label;

import io.mateu.uidl.data.ListingData;
import io.mateu.uidl.data.NoFilters;
import io.mateu.uidl.data.Pageable;
import io.mateu.uidl.interfaces.CrudAdapter;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.workflow.contentservice.application.out.LabelRepository;
import io.mateu.workflow.contentservice.application.query.LabelQueryService;
import io.mateu.workflow.contentservice.application.query.dto.LabelRow;
import io.mateu.workflow.contentservice.application.usecases.label.delete.DeleteLabelCommand;
import io.mateu.workflow.contentservice.application.usecases.label.delete.DeleteLabelUseCase;
import io.mateu.workflow.contentservice.domain.aggregates.label.vo.LabelId;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@Scope("prototype")
@RequiredArgsConstructor
public class LabelCrudAdapter implements CrudAdapter<
LabelViewModel,
LabelViewModel,
LabelViewModel,
NoFilters,
LabelRow,
String
> {

final LabelViewModel viewModel;
final DeleteLabelUseCase deleteLabelUseCase;
final LabelQueryService queryService;

@Override
public ListingData<LabelRow> search(String searchText,
    NoFilters filters,
    Pageable pageable, HttpRequest httpRequest) {
    return queryService.findAll(searchText, filters, pageable);
    }

    @Override
    public void deleteAllById(List<String> selectedIds, HttpRequest httpRequest) {
        deleteLabelUseCase.handle(new DeleteLabelCommand(selectedIds));
        }

        @Override
        public LabelViewModel getView(String id, HttpRequest httpRequest) {
        return viewModel.load(queryService
        .getById(id)
        .orElseThrow());
        }

        @Override
        public LabelViewModel getEditor(String id, HttpRequest httpRequest) {
        return viewModel.load(queryService
        .getById(id)
        .orElseThrow());
        }

        @Override
        public LabelViewModel getCreationForm(HttpRequest httpRequest) {
        return viewModel;
        }
        }

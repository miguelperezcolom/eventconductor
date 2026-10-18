package io.mateu.workflow.infra.in.ui.pages.batch;

import io.mateu.core.infra.declarative.orchestrators.crud.Crud;
import io.mateu.uidl.StyleConstants;
import io.mateu.uidl.annotations.PageWidth;
import io.mateu.uidl.annotations.PageWidthStyle;
import io.mateu.uidl.data.ListingData;
import io.mateu.uidl.data.Page;
import io.mateu.uidl.data.SearchRequest;
import io.mateu.uidl.fluent.GridLayout;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.workflow.application.services.BatchOperationService;
import io.mateu.workflow.security.CallerResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

/**
 * The Batch operations page: an operator command applied to every process a filter matches, walked in the
 * background — the most recent first, each with its progress. New takes the Processes page's filters
 * (search attributes included); the Processes page also starts one from the filter on screen.
 *
 * <p>Extends {@code Crud} directly (as {@code Schedules} does): the detail is a {@link BatchOperationDetailView},
 * not the row.
 */
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@Service
@Scope("prototype")
@RequiredArgsConstructor
@PageWidth(PageWidthStyle.FULL_WIDTH)
public class BatchOperations extends Crud<Object, BatchOperationForm, BatchOperationForm, BatchOperationFilters,
        BatchOperationRow, String> {

    final BatchOperationService batches;
    final ObjectProvider<BatchOperationDetailView> detailViewProvider;
    final ObjectProvider<CallerResolver> callerResolver;

    @Override
    public Class<BatchOperationFilters> filtersClass() {
        return BatchOperationFilters.class;
    }

    @Override
    public Class<BatchOperationRow> rowClass() {
        return BatchOperationRow.class;
    }

    @Override
    public GridLayout gridLayout() {
        return GridLayout.table;
    }

    @Override
    public String title() {
        return "Batch operations";
    }

    @Override
    public ListingData<BatchOperationRow> search(SearchRequest searchRequest, HttpRequest httpRequest) {
        var filters = searchRequest.filters() instanceof BatchOperationFilters f ? f : null;
        var needle = searchRequest.searchText() == null || searchRequest.searchText().isBlank()
                ? null : searchRequest.searchText().toLowerCase(Locale.ROOT);
        var all = batches.list().stream()
                .filter(op -> filters == null || filters.type() == null || filters.type() == op.type())
                .filter(op -> filters == null || filters.status() == null || filters.status() == op.status())
                .filter(op -> needle == null || (op.id() + " " + op.filter().describe() + " " + op.createdBy())
                        .toLowerCase(Locale.ROOT).contains(needle))
                .map(BatchOperationRow::of)
                .toList();
        var pageable = searchRequest.pageable();
        int size = pageable.size() > 0 ? pageable.size() : all.size();
        int lastPage = size > 0 ? Math.max(0, (all.size() - 1) / size) : 0;
        int pageNumber = Math.min(Math.max(pageable.page(), 0), lastPage);
        var content = all.stream().skip((long) pageNumber * size).limit(size).toList();
        return new ListingData<>(new Page<>(searchRequest.searchText(), size, pageNumber, all.size(), content));
    }

    @Override
    public Object view(String id, HttpRequest httpRequest) {
        return detailViewProvider.getObject().load(id);
    }

    @Override
    public Class<?> viewClass() {
        return BatchOperationDetailView.class;
    }

    @Override
    public String getStyleForView() {
        return StyleConstants.FULL_WIDTH_WITH_PADDING;
    }

    @Override
    public BatchOperationForm edit(String id, HttpRequest httpRequest) {
        throw new UnsupportedOperationException();
    }

    @Override
    public String save(HttpRequest httpRequest) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Class<BatchOperationForm> creationFormClass() {
        return BatchOperationForm.class;
    }

    @Override
    public BatchOperationForm creationForm(HttpRequest httpRequest) {
        return new BatchOperationForm();
    }

    /** Lands on the new batch's page, which shows its progress. */
    @Override
    public String create(HttpRequest httpRequest) {
        var form = httpRequest.getComponentState(BatchOperationForm.class);
        var resolver = callerResolver == null ? null : callerResolver.getIfAvailable();
        return batches.create(form.type, form.filter(), null, resolver == null ? null : resolver.current()).id();
    }

    @Override
    public void deleteAllById(List<String> ids, HttpRequest httpRequest) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean canEdit() {
        return false;
    }

    @Override
    public boolean canDelete() {
        return false;
    }

    @Override
    public boolean readOnly() {
        return false;
    }
}

package io.mateu.workflow.infra.in.ui.pages;

import io.mateu.core.infra.declarative.orchestrators.crud.Crud;
import io.mateu.uidl.StyleConstants;
import io.mateu.uidl.annotations.ListToolbarButton;
import io.mateu.uidl.annotations.PageWidth;
import io.mateu.uidl.annotations.PageWidthStyle;
import io.mateu.uidl.data.ListingData;
import io.mateu.uidl.data.NoFilters;
import io.mateu.uidl.data.Page;
import io.mateu.uidl.data.SearchRequest;
import io.mateu.uidl.fluent.GridLayout;
import io.mateu.uidl.interfaces.CrudStore;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.workflow.application.out.WorkflowDefinitionRepository;
import io.mateu.workflow.domain.aggregates.WorkflowDefinition;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.util.List;

// Mateu 271 made Navigable<Detail,Id>.view() return the generic Detail type. Selecting a row shows a
// rich read-only WorkflowDefinitionDetailView (summary + steps + inline graph) which is not the
// WorkflowDefinition row, so this page extends Crud directly with an Object view type instead of
// AutoCrud<WorkflowDefinition> (which pins View = Row). Definitions are authored as YAML / imported
// from the classpath, git or DB (see the *WorkflowDefinitionRepository implementations) and are not
// created or edited from this admin page, so the CRUD write capabilities stay disabled.
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@Service
@Scope("prototype")
@RequiredArgsConstructor
// Full width (uncapped) so the hosted table and the wide graph detail use the whole screen.
@PageWidth(PageWidthStyle.FULL_WIDTH)
public class WorkflowDefinitions extends Crud<Object, WorkflowDefinition, WorkflowDefinition, NoFilters, WorkflowDefinition, String> {

    final WorkflowDefinitionDetailView detailView;
    final WorkflowDefinitionRepository repository;

    public CrudStore<WorkflowDefinition> store() {
        return repository;
    }

    @Override
    public Class<WorkflowDefinition> rowClass() {
        return WorkflowDefinition.class;
    }

    @Override
    public ListingData<WorkflowDefinition> search(SearchRequest searchRequest, HttpRequest httpRequest) {
        var searchText = searchRequest.searchText();
        var pageable = searchRequest.pageable();
        var all = repository.findAll().stream()
                .filter(wd -> searchText == null || searchText.isBlank()
                        || wd.searchableText().toLowerCase().contains(searchText.toLowerCase()))
                .toList();
        var content = all.stream()
                .skip((long) pageable.page() * pageable.size())
                .limit(pageable.size())
                .toList();
        return new ListingData<>(new Page<>(searchText, content.size(), pageable.page(), all.size(), content));
    }

    // Read-only detail: summarised fields, the list of steps and an inline read-only graph.
    @Override
    public Object view(String id, HttpRequest httpRequest) {
        return detailView.load(id);
    }

    @Override
    public Class<?> viewClass() {
        return WorkflowDefinitionDetailView.class;
    }

    @Override
    public String getStyleForView() {
        return StyleConstants.FULL_WIDTH_WITH_PADDING;
    }

    // A definition is a scannable row of short fields; there is room for a real table, so pin it
    // instead of letting the auto weight-engine fall back to cards.
    @Override
    public GridLayout gridLayout() {
        return GridLayout.table;
    }

    @Override
    public boolean canEdit() {
        return false;
    }

    @Override
    public boolean canCreate() {
        return false;
    }

    @Override
    public boolean canDelete() {
        return false;
    }

    @Override
    public WorkflowDefinition edit(String id, HttpRequest httpRequest) {
        throw new UnsupportedOperationException();
    }

    @Override
    public String save(HttpRequest httpRequest) {
        throw new UnsupportedOperationException();
    }

    @Override
    public WorkflowDefinition creationForm(HttpRequest httpRequest) {
        throw new UnsupportedOperationException();
    }

    @Override
    public String create(HttpRequest httpRequest) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void deleteAllById(List<String> ids, HttpRequest httpRequest) {
        throw new UnsupportedOperationException();
    }

    @ListToolbarButton
    public void importFromGithub() throws Exception {
        throw new Exception("No configured");
    }

}

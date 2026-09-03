package io.mateu.workflow.infra.in.ui.pages;

import io.mateu.core.infra.declarative.orchestrators.crud.AutoCrud;
import io.mateu.uidl.annotations.ListToolbarButton;
import io.mateu.uidl.annotations.Style;
import io.mateu.uidl.data.ListingData;
import io.mateu.uidl.data.SearchRequest;
import io.mateu.uidl.interfaces.CrudStore;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.workflow.application.out.FormExecutionRepository;
import io.mateu.workflow.domain.FormExecution;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@Scope("prototype")
@RequiredArgsConstructor
@Style("width: 100%;")
@Slf4j
public class FormExecutions extends AutoCrud<FormExecution> {

    final FormExecutionRepository repository;

    @Override
    public CrudStore<FormExecution> store() {
        return repository;
    }

    @ListToolbarButton(rowsSelectedRequired = true)
    public void claim(List<FormExecution> selectedRows) {
      log.info("claiming " + selectedRows.size() + " rows");
    }

    /**
     * Shortens the {@code processId} shown in the listing to an ellipsis plus the last segment of
     * the UUID (…a1b2c3d4). This only rewrites the rows returned for display; the stored value is
     * untouched and the editor still loads the full id via {@code buildNamedView}, so nothing
     * shortened is ever saved back.
     */
    @Override
    public ListingData<FormExecution> search(SearchRequest request, HttpRequest httpRequest) {
        var data = super.search(request, httpRequest);
        var page = data.page();
        if (page == null || page.content() == null) {
            return data;
        }
        return data.withPage(
                page.withContent(page.content().stream().map(this::withShortProcessId).toList()));
    }

    private FormExecution withShortProcessId(FormExecution row) {
        var processId = row.processId();
        if (processId == null || processId.isBlank()) {
            return row;
        }
        int lastHyphen = processId.lastIndexOf('-');
        var shortId = lastHyphen < 0 ? processId : "…" + processId.substring(lastHyphen + 1);
        return row.withProcessId(shortId);
    }
}

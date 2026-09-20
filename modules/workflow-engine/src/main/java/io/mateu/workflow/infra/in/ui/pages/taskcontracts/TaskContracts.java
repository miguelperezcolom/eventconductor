package io.mateu.workflow.infra.in.ui.pages.taskcontracts;

import io.mateu.core.infra.declarative.orchestrators.crud.FilteredAutoCrud;
import io.mateu.uidl.annotations.ReadOnly;
import io.mateu.uidl.data.ListingData;
import io.mateu.uidl.data.Pageable;
import io.mateu.uidl.interfaces.CrudStore;
import io.mateu.uidl.interfaces.HttpRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

/**
 * The Tasks view: the task contracts the engine knows, their group and default topic, how many
 * workflow definitions use each version, and the URLs to download a generated worker project
 * (module or standalone service) for the group. Read-only — contracts are authored as
 * {@code .ectask} files, not here — and the download itself is served by {@code TaskProjectController}.
 */
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@Service
@Scope("prototype")
@RequiredArgsConstructor
@ReadOnly
public class TaskContracts extends FilteredAutoCrud<TaskContractFilters, TaskContractRow> {

    final TaskContractsCrudAdapter taskContractsCrudAdapter;

    @Override
    public Class filtersClass() {
        return TaskContractFilters.class;
    }

    @Override
    public CrudStore<TaskContractRow> store() {
        return taskContractsCrudAdapter.repository();
    }

    @Override
    public ListingData<TaskContractRow> fetchRows(String searchText, TaskContractFilters filters,
                                                  Pageable pageable, HttpRequest httpRequest) {
        return taskContractsCrudAdapter.search(searchText, filters, pageable, httpRequest);
    }

    @Override
    public String title() {
        return "Tasks";
    }
}

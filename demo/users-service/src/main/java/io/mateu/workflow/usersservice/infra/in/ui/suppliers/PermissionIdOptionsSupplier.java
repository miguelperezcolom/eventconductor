package io.mateu.workflow.usersservice.infra.in.ui.suppliers;

import io.mateu.uidl.data.ListingData;
import io.mateu.uidl.data.Option;
import io.mateu.uidl.data.Page;
import io.mateu.uidl.data.Pageable;
import io.mateu.uidl.interfaces.LookupOptionsSupplier;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.workflow.usersservice.application.query.PermissionQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PermissionIdOptionsSupplier implements LookupOptionsSupplier {

    final PermissionQueryService queryService;

    @Override
    public ListingData<Option> search(String fieldId, String searchText, Pageable pageable, HttpRequest httpRequest) {
        var found = queryService.findAll(searchText, null, pageable);
        return new ListingData<>(new Page<>(
                searchText,
                found.page().pageSize(),
                found.page().pageNumber(),
                found.page().totalElements(),
                found.page().content().stream().map(workflowDefinition ->
                        new Option(workflowDefinition.id(), workflowDefinition.name())).toList()));
    }
}

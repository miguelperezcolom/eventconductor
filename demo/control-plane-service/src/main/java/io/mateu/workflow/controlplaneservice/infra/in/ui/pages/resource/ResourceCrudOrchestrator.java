package io.mateu.workflow.controlplaneservice.infra.in.ui.pages.resource;

import io.mateu.core.infra.declarative.CrudOrchestrator;
import io.mateu.uidl.annotations.Title;
import io.mateu.uidl.data.NoFilters;
import io.mateu.uidl.interfaces.CrudAdapter;
import io.mateu.workflow.controlplaneservice.application.query.dto.ResourceRow;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Scope("prototype")
@Title("Resources")
public class ResourceCrudOrchestrator extends CrudOrchestrator<
        ResourceViewModel,
        ResourceViewModel,
        ResourceViewModel,
        NoFilters,
        ResourceRow,
        String
        > {

    final ResourceCrudAdapter adapter;

    @Override
    public CrudAdapter<ResourceViewModel,
            ResourceViewModel, ResourceViewModel,
            NoFilters, ResourceRow, String> adapter() {
        return adapter;
    }

    @Override
    public String toId(String s) {
        return s;
    }
}

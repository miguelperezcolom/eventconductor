package io.mateu.workflow.infra.in.ui.suppliers;

import io.mateu.uidl.data.ListingData;
import io.mateu.uidl.data.Option;
import io.mateu.uidl.data.Page;
import io.mateu.uidl.data.Pageable;
import io.mateu.uidl.interfaces.LookupOptionsSupplier;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.workflow.application.out.WorkflowDefinitionRepository;
import io.mateu.workflow.domain.aggregates.Step;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Service;

import java.util.List;

@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@Service
@RequiredArgsConstructor
public class WorkflowDefinitionIdOptionsSupplier implements LookupOptionsSupplier {

    final WorkflowDefinitionRepository repository;

    @Override
    public ListingData<Option> search(String fieldId, String searchText, Pageable pageable, HttpRequest httpRequest) {
        // A PROCESS step cannot launch the workflow it belongs to as its child (that would recurse
        // forever), so exclude the definition currently being edited. The step being edited carries
        // its parent definition id in workflowDefinitionId and bubbles up as the initiator state.
        var currentStep = httpRequest == null ? null : httpRequest.getInitiatorState(Step.class);
        var currentDefinitionId = currentStep == null ? null : currentStep.workflowDefinitionId();
        List<Option> all = repository.findAll().stream()
                .filter(wd -> currentDefinitionId == null || !currentDefinitionId.equals(wd.id()))
                .filter(wd -> searchText == null || searchText.isEmpty()
                        || wd.name().toLowerCase().contains(searchText.toLowerCase()))
                .map(wd -> new Option(wd.id(), wd.name()))
                .toList();
        int from = pageable.page() * pageable.size();
        int to = Math.min(from + pageable.size(), all.size());
        List<Option> slice = from < all.size() ? all.subList(from, to) : List.of();
        return new ListingData<>(new Page<>(searchText, slice.size(), pageable.page(), all.size(), slice));
    }
}

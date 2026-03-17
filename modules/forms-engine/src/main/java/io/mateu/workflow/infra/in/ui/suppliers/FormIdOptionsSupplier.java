package io.mateu.workflow.infra.in.ui.suppliers;

import io.mateu.uidl.data.ListingData;
import io.mateu.uidl.data.Option;
import io.mateu.uidl.data.Page;
import io.mateu.uidl.data.Pageable;
import io.mateu.uidl.interfaces.ForeignKeyOptionsSupplier;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.workflow.infra.out.persistence.FormEntityRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class FormIdOptionsSupplier implements ForeignKeyOptionsSupplier {

    final FormEntityRepository repository;

    @Override
    public ListingData<Option> search(String searchText, Pageable pageable, HttpRequest httpRequest) {
        var found = repository.findAll(org.springframework.data.domain.Pageable.ofSize(pageable.size()).withPage(pageable.page()));
        return new ListingData<>(new Page<>(
                searchText,
                found.getSize(),
                found.getNumber(),
                found.getTotalElements(),
                found.get().map(workflowDefinition ->
                        new Option(workflowDefinition.getId(), workflowDefinition.getName())).toList()));
    }
}

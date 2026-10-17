package io.mateu.workflow.infra.in.ui.suppliers;

import io.mateu.uidl.data.ListingData;
import io.mateu.uidl.data.Option;
import io.mateu.uidl.data.Page;
import io.mateu.uidl.data.Pageable;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.uidl.interfaces.LookupOptionsSupplier;
import io.mateu.workflow.application.out.WorkflowDefinitionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.TreeMap;

/** The tags any definition carries, each once (case-insensitively, the first spelling kept), as lookup options. */
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@Service
@RequiredArgsConstructor
public class WorkflowDefinitionTagOptionsSupplier implements LookupOptionsSupplier {

    final WorkflowDefinitionRepository repository;

    @Override
    public ListingData<Option> search(String fieldId, String searchText, Pageable pageable, HttpRequest httpRequest) {
        var byLowerCase = new TreeMap<String, String>();
        repository.findAll().forEach(wd -> wd.tagsOrEmpty().forEach(tag -> {
            if (tag != null && !tag.isBlank()) {
                byLowerCase.putIfAbsent(tag.toLowerCase(Locale.ROOT), tag);
            }
        }));
        var needle = searchText == null ? "" : searchText.toLowerCase(Locale.ROOT);
        List<Option> all = byLowerCase.entrySet().stream()
                .filter(e -> needle.isEmpty() || e.getKey().contains(needle))
                .map(e -> new Option(e.getValue(), e.getValue()))
                .toList();
        int size = pageable.size() > 0 ? pageable.size() : all.size();
        int lastPage = size > 0 ? Math.max(0, (all.size() - 1) / size) : 0;
        int pageNumber = Math.min(Math.max(pageable.page(), 0), lastPage);
        int from = Math.min(pageNumber * size, all.size());
        int to = Math.min(from + size, all.size());
        return new ListingData<>(new Page<>(searchText, size, pageNumber, all.size(), all.subList(from, to)));
    }
}

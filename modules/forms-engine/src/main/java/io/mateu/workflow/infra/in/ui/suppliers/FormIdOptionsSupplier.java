package io.mateu.workflow.infra.in.ui.suppliers;

import io.mateu.uidl.data.ListingData;
import io.mateu.uidl.data.Option;
import io.mateu.uidl.data.Page;
import io.mateu.uidl.data.Pageable;
import io.mateu.uidl.interfaces.LookupOptionsSupplier;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.workflow.application.out.FormRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Service;

import java.util.List;

@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@Service
@RequiredArgsConstructor
public class FormIdOptionsSupplier implements LookupOptionsSupplier {

    final FormRepository repository;

    @Override
    public ListingData<Option> search(String fieldId, String searchText, Pageable pageable, HttpRequest httpRequest) {
        List<Option> all = repository.findAll().stream()
                .filter(f -> searchText == null || searchText.isEmpty()
                        || f.name().toLowerCase().contains(searchText.toLowerCase()))
                .map(f -> new Option(f.id(), f.name()))
                .toList();
        // The page SIZE is the one asked for, not the rows this page happens to carry: past the
        // end that is 0, and the pager divides by it ("Page 3423 of Infinity"). A page beyond the
        // end serves the last real one, so a stale deep link recovers instead of an empty grid.
        int size = pageable.size() > 0 ? pageable.size() : all.size();
        int lastPage = size > 0 ? Math.max(0, (all.size() - 1) / size) : 0;
        int pageNumber = Math.min(Math.max(pageable.page(), 0), lastPage);
        int from = Math.min(pageNumber * size, all.size());
        int to = Math.min(from + size, all.size());
        List<Option> slice = all.subList(from, to);
        return new ListingData<>(new Page<>(searchText, size, pageNumber, all.size(), slice));
    }
}

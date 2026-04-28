package io.mateu.workflow.domain.aggregates;

import io.mateu.uidl.data.ListingData;
import io.mateu.uidl.data.Option;
import io.mateu.uidl.data.Pageable;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.uidl.interfaces.LookupOptionsSupplier;

import java.util.List;

public class StepIdOptionsSupplier implements LookupOptionsSupplier {
    @Override
    public ListingData<Option> search(String fieldName, String searchText, Pageable pageable, HttpRequest httpRequest) {
        return ListingData.of(List.of());
    }
}

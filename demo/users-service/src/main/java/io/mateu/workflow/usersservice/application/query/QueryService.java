package io.mateu.workflow.usersservice.application.query;

import io.mateu.uidl.data.ListingData;
import io.mateu.uidl.data.Pageable;

public interface QueryService<T, IdType> {

    ListingData<T> findAll(String searchText,
                           Object filters, Pageable pageable);

    String getLabel(IdType id);

}

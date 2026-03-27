package io.mateu.workflow.controlplaneservice.application.query;

import io.mateu.uidl.data.ListingData;
import io.mateu.uidl.data.Pageable;
import io.mateu.workflow.controlplaneservice.application.query.dto.ChangeDto;

public interface ChangeQueryService {

    ListingData<ChangeDto> findAll(String searchText,
                                   Object filters, Pageable pageable);

}

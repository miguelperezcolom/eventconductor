package io.mateu.workflow.controlplaneservice.application.query;

import io.mateu.uidl.data.ListingData;
import io.mateu.uidl.data.Pageable;
import io.mateu.workflow.controlplaneservice.application.query.dto.ChangeDto;
import io.mateu.workflow.controlplaneservice.application.query.dto.DeploymentDto;

public interface DeploymentQueryService {

    ListingData<DeploymentDto> findAll(String searchText,
                                       Object filters, Pageable pageable);

}

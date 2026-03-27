package io.mateu.workflow.controlplaneservice.application.usecases.release.update;

import java.time.LocalDateTime;
import java.util.List;

public record UpdateReleaseCommand(String id, String name,
                                   String userId,
                                   LocalDateTime date,
                                   String siteId,
                                   List<Long> pageIds,
                                   List<String> countryCodes,
                                   List<String> languageCodes,
                                   String environmentId) {
}

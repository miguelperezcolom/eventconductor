package io.mateu.workflow.controlplaneservice.application.usecases.release.create;

import java.time.LocalDateTime;
import java.util.List;

public record CreateReleaseCommand(
        String name,
                String userId,
                LocalDateTime date,
                String siteId,
                List<Long> pageIds,
                List<String> countryCodes,
        List<String> languageCodes,
                String environmentId) {
}

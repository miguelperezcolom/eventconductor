package io.mateu.workflow.controlplaneservice.application.query.dto;

import io.mateu.uidl.annotations.ForeignKey;
import io.mateu.uidl.annotations.HiddenInCreate;
import io.mateu.uidl.annotations.ReadOnly;
import io.mateu.workflow.controlplaneservice.infra.in.ui.suppliers.*;
import jakarta.validation.constraints.NotEmpty;

import java.time.LocalDateTime;
import java.util.List;

public record ReleaseDto(String id, String name,
                         String user,
                         LocalDateTime date,
                         String environment,
                         String site,
                         List<String> pages,
                         List<String> countries,
                         List<String> languages) {
}

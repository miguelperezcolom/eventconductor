package io.mateu.workflow.controlplaneservice.infra.in.ui.pages.changes;

import io.mateu.uidl.annotations.Title;
import io.mateu.uidl.annotations.Toolbar;
import io.mateu.uidl.annotations.Trigger;
import io.mateu.uidl.annotations.TriggerType;
import io.mateu.uidl.data.*;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.uidl.interfaces.ListingBackend;
import io.mateu.workflow.controlplaneservice.application.query.ChangeQueryService;
import io.mateu.workflow.controlplaneservice.application.query.dto.ChangeStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.util.Base64;
import java.util.List;

import static io.mateu.core.infra.JsonSerializer.fromJson;

@Title("Changes")
@Service
@Scope("prototype")
@RequiredArgsConstructor
@Trigger(type = TriggerType.OnLoad, actionId = "search")
@Slf4j
public class Changes implements ListingBackend<NoFilters, ChangeRow> {

    final ChangeQueryService queryService;
    final CreateReleaseForm createReleaseForm;

    @Override
    public ListingData<ChangeRow> search(String searchText, NoFilters filters, Pageable pageable, HttpRequest httpRequest) {
        var found = queryService.findAll(searchText, filters, pageable);
        return ListingData.<ChangeRow>builder()
                .page(Page.<ChangeRow>builder()
                        .searchSignature(found.page().searchSignature())
                        .totalElements(found.page().totalElements())
                        .pageSize(found.page().pageSize())
                        .pageNumber(found.page().pageNumber())
                        .content(found.page().content().stream()
                                .map(dto -> new ChangeRow(
                                        dto.pageId(), dto.page(), dto.country(), dto.language(),
                                        new Status(mapStatus(dto.status()), dto.status().name())))
                                .toList())
                        .build())
                .build();
    }

    private StatusType mapStatus(ChangeStatus status) {
        if (status == ChangeStatus.Released) return StatusType.SUCCESS;
        return StatusType.DANGER;
    }

    @Toolbar
    public CreateReleaseForm createRelease(List<ChangeRow> selectedRows, HttpRequest httpRequest) {
        log.info("create release {}", selectedRows);

        var auth = httpRequest.getHeaderValue("Authorization");
        var jwt = auth.split(" ")[1];

        String[] chunks = jwt.split("\\.");

        // El índice 0 es el Header, el 1 es el Payload (el JSON con los datos)
        var payload = fromJson(new String(Base64.getUrlDecoder().decode(chunks[1])));

        var user = payload.get("preferred_username").toString();

        return createReleaseForm.withUser(user);
    }
}

package io.mateu.workflow.controlplaneservice.infra.in.ui.pages.page;

import io.mateu.uidl.StyleConstants;
import io.mateu.uidl.annotations.*;
import io.mateu.uidl.data.FieldStereotype;
import io.mateu.uidl.interfaces.CrudCreationForm;
import io.mateu.uidl.interfaces.CrudEditorForm;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.uidl.interfaces.Identifiable;
import io.mateu.workflow.controlplaneservice.application.query.dto.PageDto;
import io.mateu.workflow.controlplaneservice.application.usecases.page.create.CreatePageCommand;
import io.mateu.workflow.controlplaneservice.application.usecases.page.create.CreatePageUseCase;
import io.mateu.workflow.controlplaneservice.application.usecases.page.update.UpdatePageCommand;
import io.mateu.workflow.controlplaneservice.application.usecases.page.update.UpdatePageUseCase;
import io.mateu.workflow.controlplaneservice.domain.aggregates.page.vo.PageChangeFrequency;
import io.mateu.workflow.controlplaneservice.domain.aggregates.page.vo.PageCheck;
import io.mateu.workflow.controlplaneservice.infra.in.ui.suppliers.SiteIdLabelSupplier;
import io.mateu.workflow.controlplaneservice.infra.in.ui.suppliers.SiteIdOptionsSupplier;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
@Scope("prototype")
@RequiredArgsConstructor
@FormLayout(columns = 3)
@Style(StyleConstants.CONTAINER)
public class PageViewModel implements Identifiable, CrudEditorForm<String>, CrudCreationForm<String> {
    @HiddenInCreate
    @ReadOnly
    String id;
    @NotEmpty
    String name;
    @Lookup(search = SiteIdOptionsSupplier.class, label = SiteIdLabelSupplier.class)
    @NotNull
    String siteId;
    @NotEmpty
    String path;
    boolean dependsOnLanguage;
    boolean dependsOnCountry;

    PageChangeFrequency changeFrequency;
    double priority;
    @ReadOnly
    LocalDateTime lastModification;

    @Tab
    @Colspan(3)
    List<PageCheckViewModel> checks;
    @Tab
    @NotEmpty
    @Stereotype(FieldStereotype.textarea)
            @Colspan(3)
            @Style("width:100%;")
    String jsonLd;

    final CreatePageUseCase createPageUseCase;
    final UpdatePageUseCase updatePageUseCase;

    @Override
    public String create(HttpRequest httpRequest) {
        return createPageUseCase.handle(new CreatePageCommand(siteId, name, path, jsonLd, dependsOnLanguage, dependsOnCountry, changeFrequency, priority, checks));
    }

    @Override
    public void save(HttpRequest httpRequest) {
        updatePageUseCase.handle(new UpdatePageCommand(id, siteId, name, path, jsonLd, dependsOnLanguage, dependsOnCountry, changeFrequency, priority, checks));
    }

    @Override
    public String id() {
        return id;
    }

    public PageViewModel load(PageDto page) {
        id = String.valueOf(page.id());
        siteId = page.siteId();
        name = page.name();
        path = page.path();
        jsonLd = page.jsonLd();
        dependsOnLanguage = page.dependsOnLanguage();
        dependsOnCountry = page.dependsOnCountry();
        changeFrequency = page.changeFrequency();
        priority = page.priority();
        lastModification = page.lastModification();
        return this;
    }

    @Override
    public String toString() {
        return id != null ? name : "New page";
    }
}

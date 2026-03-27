package io.mateu.workflow.controlplaneservice.infra.in.ui.pages.route;

import io.mateu.uidl.annotations.ForeignKey;
import io.mateu.uidl.annotations.HiddenInCreate;
import io.mateu.uidl.annotations.ReadOnly;
import io.mateu.uidl.interfaces.CrudCreationForm;
import io.mateu.uidl.interfaces.CrudEditorForm;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.uidl.interfaces.Identifiable;
import io.mateu.workflow.controlplaneservice.application.query.dto.RouteDto;
import io.mateu.workflow.controlplaneservice.application.usecases.route.create.CreateRouteCommand;
import io.mateu.workflow.controlplaneservice.application.usecases.route.create.CreateRouteUseCase;
import io.mateu.workflow.controlplaneservice.application.usecases.route.update.UpdateRouteCommand;
import io.mateu.workflow.controlplaneservice.application.usecases.route.update.UpdateRouteUseCase;
import io.mateu.workflow.controlplaneservice.domain.aggregates.route.Route;
import io.mateu.workflow.controlplaneservice.infra.in.ui.suppliers.*;
import jakarta.persistence.Column;
import jakarta.validation.constraints.NotEmpty;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

@Service
@Scope("prototype")
@RequiredArgsConstructor
public class RouteViewModel implements Identifiable, CrudEditorForm<String>, CrudCreationForm<String> {
        @HiddenInCreate
        @ReadOnly
        String id;
        @NotEmpty String name;
        @ForeignKey(search = LanguageIdOptionsSupplier.class, label = LanguageIdLabelSupplier.class)
    String languageCode;
    @ForeignKey(search = CountryIdOptionsSupplier.class, label = CountryIdLabelSupplier.class)
    String countryCode;
    @ForeignKey(search = PageIdOptionsSupplier.class, label = PageIdLabelSupplier.class)
    String pageId;
    String path;
    String url;

        final CreateRouteUseCase createRouteUseCase;
        final UpdateRouteUseCase updateRouteUseCase;

        @Override
        public String create(HttpRequest httpRequest) {
        return createRouteUseCase.handle(new CreateRouteCommand(name, languageCode, countryCode, Long.valueOf(pageId), path, url));
        }

        @Override
        public void save(HttpRequest httpRequest) {
        updateRouteUseCase.handle(new UpdateRouteCommand(id, name, languageCode, countryCode, Long.valueOf(pageId), path, url));
        }

        @Override
        public String id() {
        return id;
        }

        public RouteViewModel load(RouteDto route) {
        id = String.valueOf(route.id());
        name = route.name();
        languageCode = route.language();
        countryCode = route.country();
        pageId = String.valueOf(route.page());
        path = route.path();
        url = route.url();
        return this;
        }

        @Override
        public String toString() {
        return id != null ? name : "New route";
        }
        }

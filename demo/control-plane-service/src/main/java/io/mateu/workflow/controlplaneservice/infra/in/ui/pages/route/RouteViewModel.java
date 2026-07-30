package io.mateu.workflow.controlplaneservice.infra.in.ui.pages.route;

import io.mateu.uidl.annotations.Lookup;
import io.mateu.uidl.annotations.HiddenInCreate;
import io.mateu.uidl.annotations.ReadOnly;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.uidl.interfaces.Identifiable;
import io.mateu.workflow.controlplaneservice.application.query.dto.RouteDto;
import io.mateu.workflow.controlplaneservice.application.usecases.route.create.CreateRouteCommand;
import io.mateu.workflow.controlplaneservice.application.usecases.route.create.CreateRouteUseCase;
import io.mateu.workflow.controlplaneservice.application.usecases.route.update.UpdateRouteCommand;
import io.mateu.workflow.controlplaneservice.application.usecases.route.update.UpdateRouteUseCase;
import io.mateu.workflow.controlplaneservice.infra.in.ui.suppliers.*;
import jakarta.validation.constraints.NotEmpty;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

@Service
@Scope("prototype")
@RequiredArgsConstructor
public class RouteViewModel implements Identifiable {
    @HiddenInCreate
    @ReadOnly
    String id;
    @NotEmpty
    String name;
    @Lookup(search = LanguageIdOptionsSupplier.class, label = LanguageIdLabelSupplier.class)
    String languageCode;
    @Lookup(search = CountryIdOptionsSupplier.class, label = CountryIdLabelSupplier.class)
    String countryCode;
    @Lookup(search = PageIdOptionsSupplier.class, label = PageIdLabelSupplier.class)
    String pageId;
    String path;
    String url;

    final CreateRouteUseCase createRouteUseCase;
    final UpdateRouteUseCase updateRouteUseCase;

    public String create(HttpRequest httpRequest) {
        return createRouteUseCase.handle(new CreateRouteCommand(name, languageCode, countryCode, Long.valueOf(pageId), path, url));
    }

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

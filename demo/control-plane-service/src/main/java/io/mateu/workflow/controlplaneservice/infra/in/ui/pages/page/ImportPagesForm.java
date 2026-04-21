package io.mateu.workflow.controlplaneservice.infra.in.ui.pages.page;

import io.mateu.uidl.annotations.Button;
import io.mateu.uidl.annotations.Lookup;
import io.mateu.uidl.annotations.Stereotype;
import io.mateu.uidl.data.FieldStereotype;
import io.mateu.uidl.di.MateuBeanProvider;
import io.mateu.workflow.controlplaneservice.application.out.PageRepository;
import io.mateu.workflow.controlplaneservice.domain.aggregates.page.Page;
import io.mateu.workflow.controlplaneservice.domain.aggregates.page.vo.*;
import io.mateu.workflow.controlplaneservice.domain.aggregates.site.vo.SiteId;
import io.mateu.workflow.controlplaneservice.infra.in.ui.suppliers.SiteIdLabelSupplier;
import io.mateu.workflow.controlplaneservice.infra.in.ui.suppliers.SiteIdOptionsSupplier;
import jakarta.validation.constraints.NotEmpty;
import jdk.jfr.Label;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ImportPagesForm {
    final PageRepository pageRepository;

    @Lookup(search = SiteIdOptionsSupplier.class, label = SiteIdLabelSupplier.class)
    @NotEmpty
    String site;
    @Stereotype(FieldStereotype.textarea)
    String text;

    @Button
    @Label("Import")
    Object doImport() {

        var siteId = new SiteId(site);
        var pages = pageRepository.findBySiteId(siteId);

        List<String> lines = Arrays.asList(text.split("\n"));
        lines.forEach(line -> {
            if (line !=null && !line.trim().isEmpty()) {
                if (line.startsWith("http")) {
                    var noProtocol = line.substring(line.indexOf("//") + 2);
                    var route = noProtocol.substring(noProtocol.indexOf("/"));
                    var dependsOnLanguage = false;
                    if (route.startsWith("/es")) {
                        dependsOnLanguage = true;
                        route = route.substring("/es".length());
                    }
                    String finalRoute = route;
                    var found = pages.stream().filter(page -> page.getPath().path().equals(finalRoute)).findFirst();
                    if (found.isEmpty()) {
                        var name = extractName(route);
                        if (name == null || name.isEmpty()) name = "Home";
                        pageRepository.save(Page.of(
                                siteId,
                                new PageName(name),
                                new PagePath(route.isEmpty() ? "/" : route),
                                new PageJsonLd("{}"),
                                new PageDependsOnLanguage(dependsOnLanguage),
                                new PageDependsOnCountry(true),
                                PageChangeFrequency.daily,
                                new PagePriority(1),
                                new PageLastModification(LocalDateTime.now()),
                                List.of()
                        ));
                    }
                }
            }
        });

        return MateuBeanProvider.getBean(PageCrudOrchestrator.class);
    }

    private String extractName(String route) {
        if (route == null) {
            return route;
        }
        if (!route.contains("/")) {
            return route;
        }
        var tokens = Arrays.stream(route.split("/")).filter(t -> !t.isEmpty()).toList();
        if (tokens.size() > 1) {
            return tokens.get(1);
        }
        if (tokens.isEmpty()) {
            return route;
        }
        return tokens.get(0);

    }

}

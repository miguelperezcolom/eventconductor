package io.mateu.workflow.controlplaneservice.application.usecases.compare;

import io.mateu.workflow.controlplaneservice.application.out.ImageComparator;
import io.mateu.workflow.controlplaneservice.application.out.PageRepository;
import io.mateu.workflow.controlplaneservice.application.out.RouteRepository;
import io.mateu.workflow.controlplaneservice.domain.aggregates.page.vo.PageId;
import io.mateu.workflow.controlplaneservice.domain.aggregates.route.vo.RouteId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CompareUseCase {

    final RouteRepository routeRepository;
    final ImageComparator comparator;

    public ComparisonResult handle(CompareCommand command) {
        var key = UUID.randomUUID().toString();
        var route = routeRepository.findById(new RouteId(Long.valueOf(command.routeId()))).orElseThrow();
        var languageCode = route.getLanguage() != null?route.getLanguage().code():"xx";
        var alternateUrl = route.getUrl().url().replace("/" + languageCode + "/", "es".equals(languageCode)?"/en/":"/es/");
        var result = comparator.compare(key, route.getUrl().url(), alternateUrl);
        return new ComparisonResult(
                route.getName().name(),
                result.maskedUrl(),
                result.transparentMaskedUrl(),
                result.diff(),
                result.similarity());
    }

}

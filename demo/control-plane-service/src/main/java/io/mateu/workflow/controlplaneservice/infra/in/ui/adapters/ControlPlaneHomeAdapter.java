package io.mateu.workflow.controlplaneservice.infra.in.ui.adapters;

import io.mateu.uidl.data.ChartData;
import io.mateu.uidl.data.ChartDataset;
import io.mateu.workflow.controlplaneservice.infra.out.persistence.ReleaseEntity;
import io.mateu.workflow.controlplaneservice.infra.out.persistence.ReleaseEntityRepository;
import io.mateu.workflow.controlplaneservice.infra.out.persistence.RouteEntity;
import io.mateu.workflow.controlplaneservice.infra.out.persistence.RouteEntityRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ControlPlaneHomeAdapter {

    final RouteEntityRepository routeEntityRepository;
    final ReleaseEntityRepository releaseEntityRepository;

    public ControlPlaneHomeData fetch() {

        Map<String, Double> processesByDefinition = routeEntityRepository.findAll().stream()
                .collect(LinkedHashMap::new, (m, p) ->
                        m.put(releaseName(p.getReleaseId()), m.getOrDefault(releaseName(p.getReleaseId()), 0D) + 1L),
                        HashMap::putAll);
        Map<String, Double> processesByStatus = routeEntityRepository.findAll().stream()
                .collect(LinkedHashMap::new, (m, p) ->
                        m.put(released(p), m.getOrDefault(released(p), 0D) + 1L), HashMap::putAll);
        Map<String, Double> userTasksByStatus = routeEntityRepository.findAll().stream()
                .collect(LinkedHashMap::new, (m, p) ->
                        m.put(country(p), m.getOrDefault(country(p), 0D) + 1L), HashMap::putAll);

        return ControlPlaneHomeData.builder()
                .processDefinitionsCount(releaseEntityRepository.count())
                .activeProcessesCount(routeEntityRepository.findAll().stream()
                        .filter(process ->
                                !process.getPlannedReleaseId().equals(process.getReleaseId()))
                        .count())
                .completedProcessesCount(routeEntityRepository.findAll().stream()
                        .filter(process ->
                                "Changed".equals(released(process)))
                        .count())
                .processesCount(routeEntityRepository.count())
                .processesByDefinitionChartData(ChartData.builder()
                        .labels(processesByDefinition.keySet().stream().toList())
                        .datasets(List.of(ChartDataset.builder()
                                .label("label 1")
                                .data(processesByDefinition.values().stream().toList())
                                .build()))
                        .build())
                .processesByStatusChartData(ChartData.builder()
                        .labels(processesByStatus.keySet().stream().toList())
                        .datasets(List.of(ChartDataset.builder()
                                .label("Routes")
                                .data(processesByStatus.values().stream().toList())
                                .build()))
                        .build())
                .userTasksChartData(ChartData.builder()
                        .labels(processesByDefinition.keySet().stream().toList())
                        .datasets(List.of(ChartDataset.builder()
                                .label("label 1")
                                .data(processesByDefinition.values().stream().toList())
                                .build()))
                        .build())
                .build();
    }

    private String country(RouteEntity p) {
        if (p.getCountryCode() == null) {
            return "None";
        }
        return p.getCountryCode();
    }

    private String released(RouteEntity p) {
        if (p.getHash() != null && p.getHash().equals(p.getDeployedHash())) return "Changed";
        return "Released";
    }

    @Cacheable(value = "processDefinitionName", key = "#releaseId")
    public String releaseName(Long releaseId) {
        if (releaseId == null) return "None";
        return releaseEntityRepository
                .findById(releaseId)
                .map(ReleaseEntity::getName)
                .orElse("unknown");
    }

}

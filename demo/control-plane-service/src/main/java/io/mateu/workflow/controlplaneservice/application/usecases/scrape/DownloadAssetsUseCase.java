package io.mateu.workflow.controlplaneservice.application.usecases.scrape;

import io.mateu.workflow.controlplaneservice.application.out.AssetRepository;
import io.mateu.workflow.controlplaneservice.application.out.PageRepository;
import io.mateu.workflow.controlplaneservice.application.out.ResourceRepository;
import io.mateu.workflow.controlplaneservice.application.out.RouteRepository;
import io.mateu.workflow.controlplaneservice.domain.aggregates.page.Page;
import io.mateu.workflow.controlplaneservice.domain.aggregates.resource.Resource;
import io.mateu.workflow.controlplaneservice.domain.aggregates.resource.vo.*;
import io.mateu.workflow.controlplaneservice.domain.aggregates.route.vo.RouteHash;
import io.mateu.workflow.controlplaneservice.domain.aggregates.site.vo.SiteId;
import io.mateu.workflow.controlplaneservice.infra.out.scrapper.WebScrapperService;
import io.mateu.workflow.dtos.MessageType;
import io.mateu.workflow.dtos.events.integration.TaskLogEmitted;
import io.mateu.workflow.dtos.events.integration.TaskStatus;
import io.mateu.workflow.dtos.events.integration.TaskStatusChanged;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@RequiredArgsConstructor
@Slf4j
public class DownloadAssetsUseCase {

    final PageRepository pageRepository;
    final RouteRepository routeRepository;
    final ResourceRepository resourceRepository;
    final AssetRepository assetRepository;
    final StreamBridge streamBridge;
    final WebScrapperService webScrapperService;

    public void handle(DownloadAssetsCommand command) {
        streamBridge.send("upstream", new TaskLogEmitted(
                command.taskExecutionId(),
                MessageType.Info,
                "downloading assets for site " + command.siteId()));
        AtomicInteger pos = new AtomicInteger(1);
        long t0 = System.currentTimeMillis();
        long count = routeRepository.findAll().stream().count();
        var sitePages = pageRepository.findBySiteId(new SiteId(command.siteId())).stream()
                .map(Page::getId).toList();
        routeRepository.findAll().stream().filter(r -> sitePages.contains(r.getPage()))
                .forEach(r -> {

                    log.info("Processing route {}/{}. Process time is {}minutes", pos.getAndIncrement(), count, (System.currentTimeMillis() - t0) / 60000);

                    var allContent = new StringBuilder();

                  var result = webScrapperService.download(r.getUrl().url(), r.getLanguage() != null?r.getLanguage().code():"en", r.getCountry().code());

                  if (result.statusCode() != 200) {
                      streamBridge.send("upstream", new TaskLogEmitted(
                              command.taskExecutionId(),
                              MessageType.Info,
                              "could not read html from " + r.getUrl().url()));
                      return;
                  }
                    streamBridge.send("upstream", new TaskLogEmitted(
                            command.taskExecutionId(),
                            MessageType.Info,
                            "downloaded html from " + r.getUrl().url()));

                  var content = new String(result.content());

                  allContent.append(content);

                  var resourceId = new ResourceId(r.getUrl().url() + "_" + r.getCountry().code());
                  var resourceFound = resourceRepository.findById(resourceId);
                  if (resourceFound.isEmpty()) {
                      resourceRepository.save(Resource.of(
                              resourceId,
                              new ResourceName(r.getUrl().url() + "_" + r.getCountry().code()),
                              new ResourcePath(normalizePath(r.getPath().path(), r.getCountry().code())),
                              new ResourceContent(content.getBytes(StandardCharsets.UTF_8)),
                              new ResourceStatusCode(result.statusCode()),
                              new ResourceLastUpdated(LocalDateTime.now()),
                              new ResourceSize(content.length()),
                              new ResourceMilliseconds(result.milliSeconds())
                              ));
                  } else {
                      var resource = resourceFound.get();
                      resource.update(
                              new ResourceName(r.getUrl().url() + "_" + r.getCountry().code()),
                              new ResourcePath(normalizePath(r.getPath().path(), r.getCountry().code())),
                              new ResourceContent(content.getBytes(StandardCharsets.UTF_8)),
                              new ResourceStatusCode(result.statusCode()),
                              new ResourceLastUpdated(LocalDateTime.now()),
                              new ResourceSize(content.length()),
                              new ResourceMilliseconds(result.milliSeconds())
                      );
                      resourceRepository.save(resource);
                  }

                  r.updateHash(new RouteHash(md5(allContent.toString())));
                  routeRepository.save(r);
                });

        streamBridge.send("upstream", new TaskStatusChanged(
                command.taskExecutionId(),
                TaskStatus.COMPLETED,
                List.of()));
    }

    private String normalizePath(String path, String countryCode) {
        if (path.contains(".")) {
            return path;
        }
        if (!path.endsWith("/")) {
            path = path + "/";
        }
        return path + "index_" + countryCode + ".html";
    }

    @SneakyThrows
    public static String md5(String input) {
        if (input == null) return null;
        var md = MessageDigest.getInstance("MD5");
        return java.util.HexFormat.of().formatHex(md.digest(input.getBytes()));
    }

    private String readHtml(String url, String code) {
        // read from url, setting country id as cookie X-RIU-GP
        try {
            var client = java.net.http.HttpClient.newBuilder()
                    .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
                    .build();

            var request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(url))
                    .header("User-Agent", "Mozilla/5.0")
                    .header("Accept", "text/html,application/xhtml+xml")
                    .header("Cookie", "X-RIU-GP=" + code)
                    .GET()
                    .build();

            var response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return response.body();
            }

            log.warn("error downloading html from {}. status={}", url, response.statusCode());
            return "<html><body><h1>" + url + "</h1><h2>" + code + "</h2><p>ERROR: " + response.statusCode() + "</p></body></html>";
        } catch (Exception e) {
            log.error("error reading html from {}", url, e);
            return "<html><body><h1>" + url + "</h1><h2>" + code + "</h2><p>ERROR:" + e.getClass().getSimpleName() + " " + e.getMessage() + "</p></body></html>";
        }
    }

}

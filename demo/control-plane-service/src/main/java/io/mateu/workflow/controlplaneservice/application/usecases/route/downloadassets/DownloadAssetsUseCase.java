package io.mateu.workflow.controlplaneservice.application.usecases.route.downloadassets;

import io.mateu.workflow.controlplaneservice.application.out.AssetRepository;
import io.mateu.workflow.controlplaneservice.application.out.PageRepository;
import io.mateu.workflow.controlplaneservice.application.out.ResourceRepository;
import io.mateu.workflow.controlplaneservice.application.out.RouteRepository;
import io.mateu.workflow.controlplaneservice.domain.aggregates.asset.Asset;
import io.mateu.workflow.controlplaneservice.domain.aggregates.asset.vo.AssetId;
import io.mateu.workflow.controlplaneservice.domain.aggregates.asset.vo.AssetName;
import io.mateu.workflow.controlplaneservice.domain.aggregates.asset.vo.AssetPath;
import io.mateu.workflow.controlplaneservice.domain.aggregates.asset.vo.AssetUrl;
import io.mateu.workflow.controlplaneservice.domain.aggregates.page.Page;
import io.mateu.workflow.controlplaneservice.domain.aggregates.resource.Resource;
import io.mateu.workflow.controlplaneservice.domain.aggregates.resource.vo.ResourceContent;
import io.mateu.workflow.controlplaneservice.domain.aggregates.resource.vo.ResourceId;
import io.mateu.workflow.controlplaneservice.domain.aggregates.resource.vo.ResourceName;
import io.mateu.workflow.controlplaneservice.domain.aggregates.resource.vo.ResourcePath;
import io.mateu.workflow.controlplaneservice.domain.aggregates.route.vo.RouteHash;
import io.mateu.workflow.controlplaneservice.domain.aggregates.site.vo.SiteId;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Service
@Transactional
@RequiredArgsConstructor
@Slf4j
public class DownloadAssetsUseCase {

    final PageRepository pageRepository;
    final RouteRepository routeRepository;
    final ResourceRepository resourceRepository;
    final AssetRepository assetRepository;

    public void handle(DownloadAssetsCommand command) {
        log.info("downloading assets for site {}", command.siteId());
        var sitePages = pageRepository.findBySiteId(new SiteId(command.siteId())).stream()
                .map(Page::getId).toList();
        routeRepository.findAll().stream().filter(r -> sitePages.contains(r.getPage()))
                .forEach(r -> {
                    var allContent = new StringBuilder();
                  var assetFound = assetRepository.findByUrlAndCountry(r.getUrl(), r.getCountry());
                  AssetId assetId = null;
                  if (assetFound.isEmpty()) {
                      assetId = assetRepository.save(Asset.of(
                              new AssetName(r.getUrl().url()),
                              new AssetPath(r.getPath().path()),
                              new AssetUrl(r.getUrl().url()),
                              r.getCountry()));
                  } else {
                      var asset = assetFound.get();
                      asset.update(
                              new AssetName(r.getUrl().url()),
                              new AssetPath(r.getPath().path()),
                              new AssetUrl(r.getUrl().url()),
                              r.getCountry());
                      assetRepository.save(asset);
                      assetId = asset.getId();
                  }

                  var content = readHtml(r.getUrl().url(), r.getCountry().code());
                  if (content == null) {
                      log.warn("could not read html from {}", r.getUrl().url());
                      return;
                  }
                  log.info("downloaded html from {}", r.getUrl().url());

                  allContent.append(content);

                  var resourceId = new ResourceId(r.getUrl().url() + "_" + r.getCountry().code());
                  var resourceFound = resourceRepository.findById(resourceId);
                  if (resourceFound.isEmpty()) {
                      resourceRepository.save(Resource.of(
                              resourceId,
                              new ResourceName(r.getUrl().url() + "_" + r.getCountry().code()),
                              new ResourcePath(normalizePath(r.getPath().path(), r.getCountry().code())),
                              new ResourceContent(content.getBytes(StandardCharsets.UTF_8))
                              ));
                  } else {
                      var resource = resourceFound.get();
                      resource.update(
                              new ResourceName(r.getUrl().url() + "_" + r.getCountry().code()),
                              new ResourcePath(normalizePath(r.getPath().path(), r.getCountry().code())),
                              new ResourceContent(content.getBytes(StandardCharsets.UTF_8))
                      );
                      resourceRepository.save(resource);
                  }

                  r.updateHash(new RouteHash(md5(allContent.toString())));
                  routeRepository.save(r);
                });
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
        // read from url, setting country code as cookie X-RIU-GP
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

package io.mateu.workflow.controlplaneservice.infra.in.api;

import io.mateu.workflow.controlplaneservice.application.usecases.deploy.DeployUseCase;
import io.mateu.workflow.controlplaneservice.infra.out.github.GitHubPublisherService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
import java.nio.file.Paths;

@RestController
@RequestMapping("/api/publisher")
public class PublisherController {

    @Autowired
    private GitHubPublisherService gitHubService;


    @PostMapping("/deploy/{version}")
    public ResponseEntity<String> deploy(@PathVariable String version, DeployUseCase deployUseCase) {
        try {
            // Suponiendo que los archivos están en una carpeta local temporal
            Path path = Paths.get("/tmp/releases/" + version);
            gitHubService.publishAndVerify(version, path, deployUseCase);
            return ResponseEntity.ok("Despliegue de " + version + " iniciado correctamente.");
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body("Error: " + e.getMessage());
        }
    }
}
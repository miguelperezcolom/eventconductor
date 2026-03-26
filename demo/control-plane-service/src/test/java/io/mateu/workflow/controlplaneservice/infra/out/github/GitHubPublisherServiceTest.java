package io.mateu.workflow.controlplaneservice.infra.out.github;

import io.mateu.workflow.controlplaneservice.ControlPlaneServiceApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = ControlPlaneServiceApplication.class)
class GitHubPublisherServiceTest {

    @Autowired
    GitHubPublisherService service;

    @Test
    void works() throws IOException {
        System.out.println("Hola!");
        Path path = Paths.get("./tmp/riu/");
        System.out.println("path = " + path.toAbsolutePath());
        service.publishAndVerify("v" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmm")), path);
    }

}
package io.mateu.workflow.controlplaneservice.infra.out.scrapper;

import io.mateu.workflow.controlplaneservice.ControlPlaneServiceApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(classes = ControlPlaneServiceApplication.class)
class WebScrapperServiceTest {

    @Autowired
    WebScrapperService service;

    @Test
    void works() {

        service.scrape("https://mateu.io", "ES", "1");

    }

}
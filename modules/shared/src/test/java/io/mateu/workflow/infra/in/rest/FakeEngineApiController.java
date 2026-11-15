package io.mateu.workflow.infra.in.rest;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Stands in for the engine's REST API, which refuses an anonymous caller itself (EngineApiAccess) with 401. */
@RestController
public class FakeEngineApiController {

    @GetMapping("/workflow/api/processes")
    public String processes() {
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing or invalid X-Api-Key header");
    }
}

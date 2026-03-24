package io.mateu.workflow.shell.infra.in.ui;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mateu.dtos.PairDto;
import io.mateu.uidl.annotations.*;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.uidl.interfaces.PostHydrationHandler;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static io.mateu.core.infra.JsonSerializer.toJson;

record Header(String name, String value) {}

@Title("Current http request")
@Service
@Scope("prototype")
@RequiredArgsConstructor
public class CheckRequest implements PostHydrationHandler {

    @JsonIgnore
    @Hidden
    final HttpServletRequest request;

    @Tab
    @ReadOnly
    List<Header> headers = new ArrayList<>();

    @Tab("JWT")
    @Colspan(2)
            @Html
    String header;
    @Colspan(2)
            @Html
    String payload;

    @Tab("Former JWT")
    @Colspan(2)
    @Html
            @Label("Header")
    String header0;
    @Colspan(2)
    @Html
    @Label("Payload")
    String payload0;

    @Toolbar
    void refresh() {

    }

    @SneakyThrows
    @Override
    public void onHydrated(HttpRequest httpRequest) {
        headers.clear();
        request.getHeaderNames().asIterator().forEachRemaining(n -> headers.add(new Header(n, request.getHeader(n))));
        var auth = request.getHeader("Authorization");
        var jwt = auth.split(" ")[1];

        String[] chunks = jwt.split("\\.");

        // El índice 0 es el Header, el 1 es el Payload (el JSON con los datos)
        header = new String(Base64.getUrlDecoder().decode(chunks[0]));
        payload = new String(Base64.getUrlDecoder().decode(chunks[1]));

        ObjectMapper mapper = new ObjectMapper();

        payload = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(mapper.readValue(payload, Object.class));
        payload = "<pre>" + payload + "</pre>";

        auth = request.getHeader("X-Token-Before-Auth");
        jwt = auth.split(" ")[1];

        chunks = jwt.split("\\.");

        // El índice 0 es el Header, el 1 es el Payload (el JSON con los datos)
        header0 = new String(Base64.getUrlDecoder().decode(chunks[0]));
        payload0 = new String(Base64.getUrlDecoder().decode(chunks[1]));

        payload0 = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(mapper.readValue(payload0, Object.class));
        payload0 = "<pre>" + payload0 + "</pre>";
    }
}

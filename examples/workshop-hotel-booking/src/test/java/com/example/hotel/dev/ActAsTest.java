package com.example.hotel.dev;

import io.mateu.workflow.security.ForwardedTokenCallerResolver;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ActAsTest {

    private static MockHttpServletRequest from(String address) {
        var request = new MockHttpServletRequest();
        request.setRemoteAddr(address);
        return request;
    }

    @Test
    void theManagerTokenIsReadAsMiaOfTheManagersGroup() {
        var response = new ActAs(true).actAs("manager", from("127.0.0.1"));
        var cookie = response.getHeaders().getFirst("Set-Cookie");
        assertThat(cookie).startsWith(ActAs.MATEU_TOKEN_COOKIE + "=").contains("Path=/").doesNotContain("HttpOnly");

        var token = cookie.substring(cookie.indexOf('=') + 1, cookie.indexOf(';'));
        var caller = new ForwardedTokenCallerResolver(List.of("preferred_username", "sub"), List.of("scope"),
                List.of("realm_access.roles", "roles")).fromAuthorizationHeader("Bearer " + token);
        assertThat(caller.subject()).isEqualTo("mia");
        assertThat(caller.roles()).containsExactly("front-desk-managers");
        assertThat(response.getHeaders().getLocation()).hasToString("/forms/tasks");
    }

    @Test
    void itAnswersNobodyOffThisMachineOrWithoutTrustedTokens() {
        assertThat(new ActAs(true).actAs("manager", from("192.168.1.20")).getStatusCode().value()).isEqualTo(404);
        assertThat(new ActAs(false).actAs("manager", from("127.0.0.1")).getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void nobodyClearsTheToken() {
        var cookie = new ActAs(true).actAs("nobody", from("::1")).getHeaders().getFirst("Set-Cookie");
        assertThat(cookie).contains("Max-Age=0");
    }
}

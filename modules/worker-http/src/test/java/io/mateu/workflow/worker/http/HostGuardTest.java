package io.mateu.workflow.worker.http;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.URI;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HostGuardTest {

    private static HostGuard guard(List<String> allowed, String... ips) {
        return new HostGuard(allowed, host -> {
            var addresses = new InetAddress[ips.length];
            for (int i = 0; i < ips.length; i++) {
                addresses[i] = InetAddress.getByName(ips[i]);
            }
            return addresses;
        });
    }

    @Test
    void publicAddressesPassAndInternalOnesAreRefused() {
        assertThat(guard(List.of(), "93.184.216.34").refusal(URI.create("https://example.com/x"))).isNull();
        for (var internal : List.of("127.0.0.1", "10.1.2.3", "172.16.0.1", "192.168.1.1", "169.254.169.254",
                "0.0.0.0", "::1", "fd00::1", "fe80::1", "224.0.0.1")) {
            assertThat(guard(List.of(), internal).refusal(URI.create("https://evil.example/x")))
                    .as(internal).contains("internal address");
        }
        assertThat(guard(List.of(), "93.184.216.34", "10.0.0.1").refusal(URI.create("https://mixed.example/")))
                .as("one internal answer is enough").contains("internal");
    }

    @Test
    void theAllowlistMatchesExactNamesAndWildcardSuffixes() {
        var guard = guard(List.of("api.stripe.com", "*.partner.com"), "93.184.216.34");
        assertThat(guard.refusal(URI.create("https://api.stripe.com/v1"))).isNull();
        assertThat(guard.refusal(URI.create("https://eu.partner.com/x"))).isNull();
        assertThat(guard.refusal(URI.create("https://partner.com.evil.io/x"))).contains("allowed-hosts");
        assertThat(guard.refusal(URI.create("https://other.com/x"))).contains("allowed-hosts");
    }

    @Test
    void onlyHttpAndHttpsWithAHost() {
        var guard = guard(List.of(), "93.184.216.34");
        assertThat(guard.refusal(URI.create("file:///etc/passwd"))).contains("only http");
        assertThat(guard.refusal(URI.create("gopher://x/"))).contains("only http");
        assertThat(guard.refusal(URI.create("http:///nohost"))).contains("no host");
        assertThat(new HostGuard(null, host -> { throw new java.net.UnknownHostException(host); })
                .refusal(URI.create("https://nowhere.invalid/"))).isNull();
    }
}

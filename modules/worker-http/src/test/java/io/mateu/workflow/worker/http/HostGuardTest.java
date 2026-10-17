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
    }

    @Test
    void carrierGradeNatBenchmarkingAndReservedRangesAreInternal() {
        for (var internal : List.of("100.64.0.1", "100.100.100.200", "100.127.255.255", "198.18.0.1",
                "198.19.255.255", "0.1.2.3", "240.0.0.1", "255.255.255.255")) {
            assertThat(guard(List.of(), internal).refusal(URI.create("https://evil.example/x")))
                    .as(internal).contains("internal address");
        }
        for (var external : List.of("100.63.255.255", "100.128.0.1", "198.17.255.255", "198.20.0.1")) {
            assertThat(guard(List.of(), external).refusal(URI.create("https://ok.example/x"))).as(external).isNull();
        }
    }

    @Test
    void privateIpv4EmbeddedInIpv6IsInternal() throws Exception {
        // InetAddress.getByName folds ::ffff:a.b.c.d into an Inet4Address; build the IPv6 forms directly.
        for (var internal : List.of(
                ipv6("::ffff:10.0.0.1", "0:0:0:0:0:ffff:a00:1"),
                ipv6("::ffff:169.254.169.254", "0:0:0:0:0:ffff:a9fe:a9fe"),
                ipv6("::127.0.0.1", "0:0:0:0:0:0:7f00:1"),
                ipv6("64:ff9b::169.254.169.254", "64:ff9b:0:0:0:0:a9fe:a9fe"),
                ipv6("64:ff9b::192.168.0.1", "64:ff9b:0:0:0:0:c0a8:1"),
                ipv6("2002:c0a8:0101::1", "2002:c0a8:101:0:0:0:0:1"),
                ipv6("2002:7f00:0001::1", "2002:7f00:1:0:0:0:0:1"))) {
            assertThat(new HostGuard(List.of(), host -> new InetAddress[] {internal})
                    .refusal(URI.create("https://evil.example/x"))).as(internal.toString()).contains("internal address");
        }
        for (var external : List.of(
                ipv6("64:ff9b::93.184.216.34", "64:ff9b:0:0:0:0:5db8:d822"),
                ipv6("2002:5db8:d822::1", "2002:5db8:d822:0:0:0:0:1"),
                ipv6("2606:4700::1", "2606:4700:0:0:0:0:0:1"))) {
            assertThat(new HostGuard(List.of(), host -> new InetAddress[] {external})
                    .refusal(URI.create("https://ok.example/x"))).as(external.toString()).isNull();
        }
    }

    private static InetAddress ipv6(String label, String full) throws Exception {
        var parts = full.split(":");
        var bytes = new byte[16];
        for (int i = 0; i < 8; i++) {
            var v = Integer.parseInt(parts[i], 16);
            bytes[2 * i] = (byte) (v >> 8);
            bytes[2 * i + 1] = (byte) v;
        }
        return java.net.Inet6Address.getByAddress(label, bytes, null);
    }

    @Test
    void aHostThatDoesNotResolveIsRefusedNotAllowed() {
        var guard = new HostGuard(null, host -> { throw new java.net.UnknownHostException(host); });
        assertThat(guard.refusal(URI.create("https://nowhere.invalid/"))).contains("could not be resolved");
        assertThat(guard.check(URI.create("https://nowhere.invalid/")).unresolved()).isTrue();
        assertThat(new HostGuard(null, host -> new InetAddress[0]).refusal(URI.create("https://empty.invalid/")))
                .contains("could not be resolved");
        assertThat(guard(List.of(), "10.0.0.1").check(URI.create("https://x.example/")).unresolved()).isFalse();
    }
}

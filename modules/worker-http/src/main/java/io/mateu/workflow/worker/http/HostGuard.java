package io.mateu.workflow.worker.http;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;

/**
 * Where an absolute URL in a definition may go (SSRF): definitions can come from git, so a URL is
 * untrusted input. A host must match {@code allowed-hosts} when that list is set, and — always — every
 * address it resolves to must be public: loopback, private (RFC 1918, IPv6 ULA), link-local (which
 * includes the cloud metadata endpoint 169.254.169.254), any-local, "this network" (0.0.0.0/8),
 * carrier-grade NAT (100.64.0.0/10, which includes another cloud metadata address), benchmarking
 * (198.18.0.0/15) and multicast are refused — also when they are embedded in an IPv6 address
 * (IPv4-mapped, IPv4-compatible, NAT64 {@code 64:ff9b::/96}, 6to4 {@code 2002::/16}). A host that does
 * not resolve is refused too: the check cannot vouch for it. Internal services are reached through a
 * named connection, which is configuration and therefore trusted.
 *
 * <p>The check runs on the addresses resolved when the call is made. (A resolver that answers
 * differently between this check and the connection — DNS rebinding — is a residual risk this cannot
 * close without pinning the connection to the checked address; pair it with an egress policy.)
 */
public final class HostGuard {

    private final List<String> allowedHosts;
    private final Resolver resolver;

    /** Name resolution, replaceable in tests. */
    public interface Resolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    public HostGuard(List<String> allowedHosts, Resolver resolver) {
        this.allowedHosts = allowedHosts == null ? List.of() : allowedHosts;
        this.resolver = resolver;
    }

    /**
     * Why a URL may not be called. {@code unresolved} marks a refusal because the host did not resolve,
     * which can be transient (a DNS hiccup) rather than a policy decision.
     */
    public record Refusal(String reason, boolean unresolved) {
    }

    /** Null when the URL may be called; otherwise why not. */
    public String refusal(URI uri) {
        var refusal = check(uri);
        return refusal == null ? null : refusal.reason();
    }

    /** Null when the URL may be called; otherwise the refusal. */
    public Refusal check(URI uri) {
        var reason = policyRefusal(uri);
        if (reason != null) {
            return new Refusal(reason, false);
        }
        InetAddress[] addresses;
        try {
            addresses = resolver.resolve(uri.getHost());
        } catch (UnknownHostException e) {
            return new Refusal("host '" + uri.getHost() + "' could not be resolved, so it cannot be checked", true);
        }
        if (addresses == null || addresses.length == 0) {
            return new Refusal("host '" + uri.getHost() + "' could not be resolved, so it cannot be checked", true);
        }
        for (var address : addresses) {
            if (isInternal(address)) {
                return new Refusal("host '" + uri.getHost() + "' resolves to an internal address ("
                        + address.getHostAddress() + "); use a named connection to reach internal services", false);
            }
        }
        return null;
    }

    private String policyRefusal(URI uri) {
        var scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !scheme.equals("http")) {
            return "only http and https URLs can be called (" + uri.getScheme() + ")";
        }
        var host = uri.getHost();
        if (host == null || host.isBlank()) {
            return "the URL has no host";
        }
        if (!allowedHosts.isEmpty() && allowedHosts.stream().noneMatch(pattern -> matches(pattern, host))) {
            return "host '" + host + "' is not in workflow.http.allowed-hosts";
        }
        return null;
    }

    static boolean matches(String pattern, String host) {
        var p = pattern.toLowerCase(Locale.ROOT);
        var h = host.toLowerCase(Locale.ROOT);
        return p.startsWith("*.") ? h.endsWith(p.substring(1)) : h.equals(p);
    }

    static boolean isInternal(InetAddress address) {
        if (address.isLoopbackAddress() || address.isSiteLocalAddress() || address.isLinkLocalAddress()
                || address.isAnyLocalAddress() || address.isMulticastAddress()) {
            return true;
        }
        var b = address.getAddress();
        if (address instanceof Inet4Address) {
            var first = b[0] & 0xff;
            var second = b[1] & 0xff;
            return first == 0                                        // 0.0.0.0/8 "this network"
                    || (first == 100 && (second & 0xc0) == 64)       // 100.64.0.0/10 carrier-grade NAT
                    || (first == 198 && (second & 0xfe) == 18)       // 198.18.0.0/15 benchmarking
                    || first >= 240;                                 // 240.0.0.0/4 reserved, broadcast
        }
        // IPv6 unique local addresses (fc00::/7) are not "site local" to the JDK.
        if ((b[0] & 0xfe) == 0xfc) {
            return true;
        }
        var embedded = embeddedIpv4(b);
        return embedded != null && isInternal(embedded);
    }

    /** The IPv4 address an IPv6 address carries (mapped, compatible, NAT64, 6to4), or null. */
    private static InetAddress embeddedIpv4(byte[] b) {
        if (b.length != 16) {
            return null;
        }
        try {
            if (zero(b, 0, 10) && (((b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff) || zero(b, 10, 12))) {
                // ::ffff:a.b.c.d (mapped) and ::a.b.c.d (compatible, deprecated)
                return Inet4Address.getByAddress(new byte[] {b[12], b[13], b[14], b[15]});
            }
            if (b[0] == 0x00 && b[1] == 0x64 && (b[2] & 0xff) == 0xff && (b[3] & 0xff) == 0x9b && zero(b, 4, 12)) {
                // 64:ff9b::/96 NAT64 well-known prefix
                return Inet4Address.getByAddress(new byte[] {b[12], b[13], b[14], b[15]});
            }
            if (b[0] == 0x20 && b[1] == 0x02) {
                // 2002::/16 6to4: the IPv4 address follows the prefix
                return Inet4Address.getByAddress(new byte[] {b[2], b[3], b[4], b[5]});
            }
        } catch (UnknownHostException impossible) {
            return null;
        }
        return null;
    }

    private static boolean zero(byte[] b, int from, int to) {
        for (int i = from; i < to; i++) {
            if (b[i] != 0) return false;
        }
        return true;
    }
}

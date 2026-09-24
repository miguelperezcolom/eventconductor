package io.mateu.workflow.worker.http;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;

/**
 * Where an absolute URL in a definition may go (SSRF): definitions can come from git, so a URL is
 * untrusted input. A host must match {@code allowed-hosts} when that list is set, and — always — every
 * address it resolves to must be public: loopback, private (RFC 1918, IPv6 ULA), link-local (which
 * includes the cloud metadata endpoint 169.254.169.254), any-local and multicast are refused. Internal
 * services are reached through a named connection, which is configuration and therefore trusted.
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

    /** Null when the URL may be called; otherwise why not. */
    public String refusal(URI uri) {
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
        InetAddress[] addresses;
        try {
            addresses = resolver.resolve(host);
        } catch (UnknownHostException e) {
            return null; // the call itself will fail with a clear I/O error
        }
        for (var address : addresses) {
            if (isInternal(address)) {
                return "host '" + host + "' resolves to an internal address (" + address.getHostAddress()
                        + "); use a named connection to reach internal services";
            }
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
        // IPv6 unique local addresses (fc00::/7) are not "site local" to the JDK.
        return address instanceof Inet6Address && (address.getAddress()[0] & 0xfe) == 0xfc;
    }
}

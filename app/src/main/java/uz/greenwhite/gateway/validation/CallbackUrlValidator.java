package uz.greenwhite.gateway.validation;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import uz.greenwhite.gateway.exception.RequestValidationException;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.util.List;
import java.util.Locale;

/**
 * SSRF guard for client-supplied callback URLs (X-Callback-Url header).
 *
 * <p>Rejects anything that is not plain http/https to a public address. When
 * {@code gateway.security.callback-allowed-hosts} is non-empty, the host must
 * additionally be on that list. Validation runs at accept time in
 * {@code ProxyController} and again at send time in {@code CallbackSender}
 * to narrow the DNS-rebinding window between the two.</p>
 */
@Slf4j
@Component
public class CallbackUrlValidator {

    private final List<String> allowedHosts;

    public CallbackUrlValidator(
            @Value("${gateway.security.callback-allowed-hosts:}") List<String> allowedHosts) {
        this.allowedHosts = allowedHosts == null ? List.of()
                : allowedHosts.stream().filter(h -> !h.isBlank())
                        .map(h -> h.toLowerCase(Locale.ROOT)).toList();
    }

    public void validate(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (Exception e) {
            throw new RequestValidationException("Invalid X-Callback-Url: not a URL");
        }

        String scheme = uri.getScheme();
        if (scheme == null || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
            throw new RequestValidationException("Invalid X-Callback-Url: only http/https allowed");
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new RequestValidationException("Invalid X-Callback-Url: missing host");
        }

        if (!allowedHosts.isEmpty()) {
            if (!allowedHosts.contains(host.toLowerCase(Locale.ROOT))) {
                throw new RequestValidationException("X-Callback-Url host is not on the allowlist: " + host);
            }
            // Host is an explicit operator-approved allowlist entry — that is a
            // stronger trust signal than a DNS-resolved public IP, so skip the
            // private/loopback resolution check below (it would otherwise also
            // reject allowlisted hosts that don't resolve from this environment,
            // e.g. behind split-horizon DNS or in tests).
            return;
        }

        // Resolve ALL A/AAAA answers — checking only the first record lets an
        // attacker hide a private IP behind a public one in a multi-answer reply.
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (Exception e) {
            throw new RequestValidationException("Invalid X-Callback-Url: unresolvable host " + host);
        }

        for (InetAddress address : addresses) {
            if (isDisallowed(address)) {
                log.warn("Blocked SSRF attempt: callback URL {} resolves to {}", url, address.getHostAddress());
                throw new RequestValidationException(
                        "X-Callback-Url must point to a public address (got " + host + ")");
            }
        }
    }

    /**
     * True when the resolved address must not be a callback target: loopback,
     * RFC1918 site-local, link-local (incl. 169.254.169.254 cloud metadata),
     * wildcard, multicast, IPv6 unique-local (fc00::/7), IPv4 CGNAT
     * (100.64.0.0/10), or the IPv4 limited broadcast address.
     *
     * <p>IPv4-mapped IPv6 addresses ({@code ::ffff:a.b.c.d}) are normalized by
     * Java's resolver to {@link java.net.Inet4Address}, so the IPv4 checks
     * below cover them too.</p>
     */
    private static boolean isDisallowed(InetAddress a) {
        if (a.isLoopbackAddress() || a.isSiteLocalAddress()
                || a.isLinkLocalAddress() || a.isAnyLocalAddress()
                || a.isMulticastAddress()) {
            return true;
        }

        byte[] raw = a.getAddress();

        // IPv6 unique-local fc00::/7 — NOT covered by isSiteLocalAddress()
        // (Java only flags the deprecated fec0::/10 as site-local for IPv6).
        if (a instanceof Inet6Address && (raw[0] & 0xFE) == 0xFC) {
            return true;
        }

        if (raw.length == 4) {
            int first = raw[0] & 0xFF;
            int second = raw[1] & 0xFF;
            // IPv4 CGNAT 100.64.0.0/10 (RFC 6598)
            if (first == 100 && second >= 64 && second <= 127) {
                return true;
            }
            // IPv4 limited broadcast
            if (first == 255 && second == 255 && (raw[2] & 0xFF) == 255 && (raw[3] & 0xFF) == 255) {
                return true;
            }
        }

        return false;
    }
}

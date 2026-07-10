package uz.greenwhite.gateway.validation;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import uz.greenwhite.gateway.exception.RequestValidationException;

import java.net.InetAddress;
import java.net.URI;
import java.util.List;
import java.util.Locale;

/**
 * SSRF guard for client-supplied callback URLs (X-Callback-Url header).
 *
 * <p>Rejects anything that is not plain http/https to a public address. When
 * {@code gateway.security.callback-allowed-hosts} is non-empty, the host must
 * additionally be on that list. Resolution happens once at accept time; the
 * later delivery by {@code CallbackSender} reuses the same URL.</p>
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

        InetAddress address;
        try {
            address = InetAddress.getByName(host);
        } catch (Exception e) {
            throw new RequestValidationException("Invalid X-Callback-Url: unresolvable host " + host);
        }

        if (address.isLoopbackAddress() || address.isSiteLocalAddress()
                || address.isLinkLocalAddress() || address.isAnyLocalAddress()
                || address.isMulticastAddress()) {
            log.warn("Blocked SSRF attempt: callback URL {} resolves to {}", url, address.getHostAddress());
            throw new RequestValidationException(
                    "X-Callback-Url must point to a public address (got " + host + ")");
        }
    }
}

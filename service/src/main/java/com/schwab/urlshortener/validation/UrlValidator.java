package com.schwab.urlshortener.validation;

import com.schwab.urlshortener.model.ServiceExceptions.InvalidUrlException;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;

/**
 * Validates candidate long URLs before they are shortened.
 *
 * <p>Beyond basic well-formedness, this includes a lightweight SSRF guard:
 * URLs that resolve to loopback, link-local or private-network addresses
 * are rejected, because a public redirect service that will happily point
 * at an internal host (e.g. http://169.254.169.254/... or
 * http://localhost:8080/admin) is a well-known attack vector against
 * services that fetch or otherwise act on the target of a short link.
 */
public final class UrlValidator {

    private static final int MAX_LENGTH = 2048;

    private UrlValidator() {
    }

    public static void validate(String longUrl) {
        if (longUrl == null || longUrl.isBlank()) {
            throw new InvalidUrlException("longUrl must not be blank");
        }
        if (longUrl.length() > MAX_LENGTH) {
            throw new InvalidUrlException("longUrl exceeds max length of " + MAX_LENGTH);
        }

        URI uri;
        try {
            uri = new URI(longUrl);
        } catch (URISyntaxException e) {
            throw new InvalidUrlException("longUrl is not a syntactically valid URI: " + e.getMessage());
        }

        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new InvalidUrlException("longUrl must use http or https, got: " + scheme);
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new InvalidUrlException("longUrl must include a host");
        }

        rejectIfInternalHost(host);
    }

    private static void rejectIfInternalHost(String host) {
        String lower = host.toLowerCase();
        if (lower.equals("localhost") || lower.endsWith(".localhost") || lower.equals("0.0.0.0")) {
            throw new InvalidUrlException("longUrl must not target a local/internal host");
        }
        try {
            InetAddress addr = InetAddress.getByName(host);
            if (addr.isLoopbackAddress() || addr.isLinkLocalAddress() || addr.isSiteLocalAddress()
                    || addr.isAnyLocalAddress()) {
                throw new InvalidUrlException("longUrl must not target a local/internal host");
            }
        } catch (UnknownHostException e) {
            // Host doesn't resolve right now (or DNS is unavailable in this environment). We do not
            // fail creation on that alone -- a target host can be registered/reachable later, and
            // failing hard here would make the validator dependent on live DNS/network availability.
            // This is a documented trade-off; see docs/testing-and-limitations.md.
        }
    }
}

package com.schwab.urlshortener.validation;

import com.schwab.urlshortener.model.ServiceExceptions.InvalidUrlException;

import java.util.Set;
import java.util.regex.Pattern;

/** Validates user-supplied custom aliases (vanity short codes). */
public final class AliasValidator {

    private static final Pattern ALLOWED = Pattern.compile("^[A-Za-z0-9_-]{3,32}$");

    // Path prefixes the HTTP layer treats specially. com.sun.net.httpserver.HttpServer matches
    // contexts by raw string prefix, not by path segment (e.g. a context registered at "/healthz"
    // also matches a request for "/healthz-anything"). An alias must never fall under one of these
    // prefixes, or it would be silently shadowed by that context instead of reaching the redirect
    // handler. Discovered via UrlShortenerIntegrationTest#rateLimitTripsAfterCapacityExceeded,
    // which initially used request paths that collided with "/healthz" -- see
    // docs/testing-and-limitations.md for the write-up.
    //
    // "expired" was added as part of the brownfield GET /api/v1/urls/expired addition
    // (UrlItemHandler), for the same reason: it is handled as a reserved sub-resource name
    // within that handler and must never be assignable as a real alias.
    private static final Set<String> RESERVED_PREFIXES = Set.of("api", "healthz", "metrics", "favicon.ico", "expired");

    private AliasValidator() {
    }

    public static void validate(String alias) {
        if (alias == null) {
            return; // absent alias is valid -- server will generate one
        }
        if (!ALLOWED.matcher(alias).matches()) {
            throw new InvalidUrlException(
                    "customAlias must be 3-32 chars of letters, digits, '-' or '_' : " + alias);
        }
        String lower = alias.toLowerCase();
        for (String reserved : RESERVED_PREFIXES) {
            if (lower.startsWith(reserved)) {
                throw new InvalidUrlException(
                        "customAlias '" + alias + "' would be shadowed by the reserved '/" + reserved + "' path");
            }
        }
    }
}

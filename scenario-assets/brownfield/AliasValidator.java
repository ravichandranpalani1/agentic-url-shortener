package com.schwab.urlshortener.validation;

import com.schwab.urlshortener.model.ServiceExceptions.InvalidUrlException;

import java.util.Set;
import java.util.regex.Pattern;

/** Validates user-supplied custom aliases (vanity short codes). */
public final class AliasValidator {

    private static final Pattern ALLOWED = Pattern.compile("^[A-Za-z0-9_-]{3,32}$");

    // Literal path segments the web layer registers a dedicated controller mapping for.
    //
    // This used to be a *prefix* check (reserved words like "healthz" would reject
    // "healthzone" too), because the original zero-dependency HTTP layer
    // (com.sun.net.httpserver.HttpServer) matches registered contexts by raw string prefix --
    // a context at "/healthz" also matches a request for "/healthz-anything", so any alias
    // merely starting with a reserved word had to be rejected. See
    // docs/testing-and-limitations.md for that bug's original discovery
    // (UrlShortenerIntegrationTest#rateLimitTripsAfterCapacityExceeded).
    //
    // Spring MVC's request mapping does not have that bug class: a literal "/api/v1/urls/expired"
    // mapping and the "/api/v1/urls/{code}" pattern are matched by exact path-segment comparison,
    // so "expired-links" is a perfectly reachable code and does NOT collide with the literal
    // "/api/v1/urls/expired" mapping the way it would have under the old HTTP layer. Only an
    // *exact* match with a reserved literal segment is still a real collision (a code literally
    // equal to a reserved word would always be shadowed by the more specific literal mapping and
    // become unreachable via GET). The set below was narrowed from prefix-match to exact-match
    // when the service moved to Spring Boot.
    //
    // "expired" was added by the orchestrator's brownfield scenario as part of the
    // GET /api/v1/urls/expired addition (UrlItemController), for the same reason: it is now a
    // reserved literal path segment and must never be assignable as a real alias, or that alias
    // would be permanently unreachable via GET.
    private static final Set<String> RESERVED_EXACT = Set.of("api", "healthz", "metrics", "favicon.ico", "expired");

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
        if (RESERVED_EXACT.contains(lower)) {
            throw new InvalidUrlException(
                    "customAlias '" + alias + "' collides with the reserved '/" + lower + "' path");
        }
    }
}

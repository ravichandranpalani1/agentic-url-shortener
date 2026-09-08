package com.schwab.urlshortener.web;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Derives the public base URL (scheme://host:port) from the incoming
 * request rather than a fixed config value, so {@code shortUrl} in API
 * responses is correct both for a normal fixed-port deployment and for the
 * random ephemeral ports Spring Boot's test slices bind to.
 */
final class BaseUrl {

    private BaseUrl() {
    }

    static String of(HttpServletRequest request) {
        String scheme = request.getScheme();
        String host = request.getServerName();
        int port = request.getServerPort();
        boolean defaultPort = ("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443);
        return defaultPort ? scheme + "://" + host : scheme + "://" + host + ":" + port;
    }
}

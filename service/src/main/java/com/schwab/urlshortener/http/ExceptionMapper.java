package com.schwab.urlshortener.http;

import com.schwab.urlshortener.model.ServiceExceptions.AliasConflictException;
import com.schwab.urlshortener.model.ServiceExceptions.InvalidUrlException;
import com.schwab.urlshortener.model.ServiceExceptions.RateLimitExceededException;
import com.schwab.urlshortener.model.ServiceExceptions.UrlGoneException;
import com.schwab.urlshortener.model.ServiceExceptions.UrlNotFoundException;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;

/** Maps domain exceptions to HTTP status codes + a structured error body, in one place. */
final class ExceptionMapper {

    private ExceptionMapper() {
    }

    static void write(HttpExchange exchange, Throwable t) throws IOException {
        if (t instanceof InvalidUrlException e) {
            HttpUtil.sendError(exchange, 400, "INVALID_REQUEST", e.getMessage());
        } else if (t instanceof AliasConflictException e) {
            HttpUtil.sendError(exchange, 409, "ALIAS_CONFLICT", e.getMessage());
        } else if (t instanceof UrlNotFoundException e) {
            HttpUtil.sendError(exchange, 404, "NOT_FOUND", e.getMessage());
        } else if (t instanceof UrlGoneException e) {
            HttpUtil.sendError(exchange, 410, "GONE", e.getMessage());
        } else if (t instanceof RateLimitExceededException e) {
            exchange.getResponseHeaders().set("Retry-After", String.valueOf(e.retryAfterSeconds));
            HttpUtil.sendError(exchange, 429, "RATE_LIMITED", e.getMessage());
        } else {
            HttpUtil.sendError(exchange, 500, "INTERNAL_ERROR", "An unexpected error occurred");
        }
    }
}

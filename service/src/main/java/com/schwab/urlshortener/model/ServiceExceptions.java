package com.schwab.urlshortener.model;

/** Holds the small set of domain exceptions used across the service. Grouped in one file for brevity. */
public final class ServiceExceptions {

    private ServiceExceptions() {
    }

    public static final class InvalidUrlException extends RuntimeException {
        public InvalidUrlException(String message) {
            super(message);
        }
    }

    public static final class AliasConflictException extends RuntimeException {
        public AliasConflictException(String message) {
            super(message);
        }
    }

    public static final class UrlNotFoundException extends RuntimeException {
        public UrlNotFoundException(String message) {
            super(message);
        }
    }

    public static final class UrlGoneException extends RuntimeException {
        public UrlGoneException(String message) {
            super(message);
        }
    }

    public static final class RateLimitExceededException extends RuntimeException {
        public final long retryAfterSeconds;

        public RateLimitExceededException(String message, long retryAfterSeconds) {
            super(message);
            this.retryAfterSeconds = retryAfterSeconds;
        }
    }
}

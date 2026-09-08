package com.schwab.urlshortener;

import com.schwab.urlshortener.model.ServiceExceptions.InvalidUrlException;
import com.schwab.urlshortener.validation.UrlValidator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class UrlValidatorTest {

    @Test
    void acceptsWellFormedHttpsUrl() {
        UrlValidator.validate("https://www.schwab.com/pricing?x=1#frag");
        // no exception == pass
    }

    @Test
    void rejectsBlank() {
        assertThrows(InvalidUrlException.class, () -> UrlValidator.validate("   "), "blank url");
    }

    @Test
    void rejectsNonHttpScheme() {
        assertThrows(InvalidUrlException.class, () -> UrlValidator.validate("ftp://example.com/file"),
                "non-http(s) scheme");
    }

    @Test
    void rejectsMissingHost() {
        assertThrows(InvalidUrlException.class, () -> UrlValidator.validate("https:///no-host"), "missing host");
    }

    @Test
    void rejectsLoopbackHost() {
        assertThrows(InvalidUrlException.class, () -> UrlValidator.validate("http://127.0.0.1/admin"),
                "loopback address (SSRF guard)");
    }

    @Test
    void rejectsLocalhostByName() {
        assertThrows(InvalidUrlException.class, () -> UrlValidator.validate("http://localhost:9999/admin"),
                "localhost by name (SSRF guard)");
    }

    @Test
    void rejectsTooLongUrl() {
        String longPath = "a".repeat(3000);
        assertThrows(InvalidUrlException.class, () -> UrlValidator.validate("https://example.com/" + longPath),
                "url exceeding max length");
    }
}

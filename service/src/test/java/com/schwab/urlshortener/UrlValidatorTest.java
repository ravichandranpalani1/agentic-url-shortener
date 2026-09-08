package com.schwab.urlshortener;

import com.schwab.testlib.Test;
import com.schwab.urlshortener.model.ServiceExceptions.InvalidUrlException;
import com.schwab.urlshortener.validation.UrlValidator;

import static com.schwab.testlib.Assert.assertThrows;

public class UrlValidatorTest {

    @Test
    public void acceptsWellFormedHttpsUrl() {
        UrlValidator.validate("https://www.schwab.com/pricing?x=1#frag");
        // no exception == pass
    }

    @Test
    public void rejectsBlank() {
        assertThrows(InvalidUrlException.class, () -> UrlValidator.validate("   "), "blank url");
    }

    @Test
    public void rejectsNonHttpScheme() {
        assertThrows(InvalidUrlException.class, () -> UrlValidator.validate("ftp://example.com/file"),
                "non-http(s) scheme");
    }

    @Test
    public void rejectsMissingHost() {
        assertThrows(InvalidUrlException.class, () -> UrlValidator.validate("https:///no-host"), "missing host");
    }

    @Test
    public void rejectsLoopbackHost() {
        assertThrows(InvalidUrlException.class, () -> UrlValidator.validate("http://127.0.0.1/admin"),
                "loopback address (SSRF guard)");
    }

    @Test
    public void rejectsLocalhostByName() {
        assertThrows(InvalidUrlException.class, () -> UrlValidator.validate("http://localhost:9999/admin"),
                "localhost by name (SSRF guard)");
    }

    @Test
    public void rejectsTooLongUrl() {
        String longPath = "a".repeat(3000);
        assertThrows(InvalidUrlException.class, () -> UrlValidator.validate("https://example.com/" + longPath),
                "url exceeding max length");
    }
}

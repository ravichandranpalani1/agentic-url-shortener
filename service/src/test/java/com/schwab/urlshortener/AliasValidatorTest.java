package com.schwab.urlshortener;

import com.schwab.testlib.Test;
import com.schwab.urlshortener.model.ServiceExceptions.InvalidUrlException;
import com.schwab.urlshortener.validation.AliasValidator;

import static com.schwab.testlib.Assert.assertThrows;

public class AliasValidatorTest {

    @Test
    public void nullAliasIsValid() {
        AliasValidator.validate(null);
    }

    @Test
    public void acceptsSimpleAlphaNumericAlias() {
        AliasValidator.validate("research-q3_2026");
    }

    @Test
    public void rejectsTooShortAlias() {
        assertThrows(InvalidUrlException.class, () -> AliasValidator.validate("ab"), "alias shorter than 3 chars");
    }

    @Test
    public void rejectsIllegalCharacters() {
        assertThrows(InvalidUrlException.class, () -> AliasValidator.validate("has space"), "alias with a space");
        assertThrows(InvalidUrlException.class, () -> AliasValidator.validate("slash/here"), "alias with a slash");
    }

    @Test
    public void rejectsReservedWords() {
        assertThrows(InvalidUrlException.class, () -> AliasValidator.validate("api"), "reserved word 'api'");
        assertThrows(InvalidUrlException.class, () -> AliasValidator.validate("healthz"), "reserved word 'healthz'");
    }

    /**
     * Regression test for a routing collision found via UrlShortenerIntegrationTest:
     * com.sun.net.httpserver matches contexts by string prefix, so an alias like
     * "healthzone" would be silently shadowed by the "/healthz" context and never reach the
     * redirect handler. See docs/testing-and-limitations.md.
     */
    @Test
    public void rejectsAliasesThatWouldBeShadowedByAReservedPathPrefix() {
        assertThrows(InvalidUrlException.class, () -> AliasValidator.validate("healthzone"),
                "alias starting with the reserved prefix 'healthz'");
        assertThrows(InvalidUrlException.class, () -> AliasValidator.validate("metrics-2026"),
                "alias starting with the reserved prefix 'metrics'");
        assertThrows(InvalidUrlException.class, () -> AliasValidator.validate("apidocs"),
                "alias starting with the reserved prefix 'api'");
    }
}

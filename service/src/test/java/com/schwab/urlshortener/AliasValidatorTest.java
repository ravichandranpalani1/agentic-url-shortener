package com.schwab.urlshortener;

import com.schwab.urlshortener.model.ServiceExceptions.InvalidUrlException;
import com.schwab.urlshortener.validation.AliasValidator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AliasValidatorTest {

    @Test
    void nullAliasIsValid() {
        AliasValidator.validate(null);
    }

    @Test
    void acceptsSimpleAlphaNumericAlias() {
        AliasValidator.validate("research-q3_2026");
    }

    @Test
    void rejectsTooShortAlias() {
        assertThrows(InvalidUrlException.class, () -> AliasValidator.validate("ab"), "alias shorter than 3 chars");
    }

    @Test
    void rejectsIllegalCharacters() {
        assertThrows(InvalidUrlException.class, () -> AliasValidator.validate("has space"), "alias with a space");
        assertThrows(InvalidUrlException.class, () -> AliasValidator.validate("slash/here"), "alias with a slash");
    }

    @Test
    void rejectsReservedWordsExactly() {
        assertThrows(InvalidUrlException.class, () -> AliasValidator.validate("api"), "reserved word 'api'");
        assertThrows(InvalidUrlException.class, () -> AliasValidator.validate("healthz"), "reserved word 'healthz'");
        assertThrows(InvalidUrlException.class, () -> AliasValidator.validate("metrics"), "reserved word 'metrics'");
        // "expired" becomes reserved only once the brownfield scenario adds the
        // GET /api/v1/urls/expired endpoint -- see ExpiredUrlsFeatureTest, added by that scenario.
    }

    /**
     * Under Spring MVC's path matching, a literal mapping like "/api/v1/urls/expired" only ever
     * collides with the "{code}" pattern on an *exact* path match -- unlike the earlier
     * zero-dependency HTTP layer (com.sun.net.httpserver.HttpServer), which matched registered
     * contexts by raw string prefix, so "healthzone" used to be silently shadowed by a "/healthz"
     * context. That whole bug class does not exist under Spring's router, so these aliases are
     * now legitimately reachable and must be *accepted*. See docs/testing-and-limitations.md.
     */
    @Test
    void acceptsAliasesThatMerelyStartWithAReservedWord() {
        assertDoesNotThrow(() -> AliasValidator.validate("healthzone"),
                "not an exact collision with the reserved 'healthz' -- Spring routes it correctly");
        assertDoesNotThrow(() -> AliasValidator.validate("metrics-2026"),
                "not an exact collision with the reserved 'metrics'");
        assertDoesNotThrow(() -> AliasValidator.validate("apidocs"),
                "not an exact collision with the reserved 'api'");
    }
}

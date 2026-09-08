package com.schwab.urlshortener;

import com.schwab.urlshortener.util.Base62Encoder;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Base62EncoderTest {

    @Test
    void encodesZero() {
        assertEquals("0", Base62Encoder.encode(0), "zero should encode to the first alphabet char");
    }

    @Test
    void encodesSmallValues() {
        assertEquals("1", Base62Encoder.encode(1), "1");
        assertEquals("Z", Base62Encoder.encode(35), "35 (0-9 then A-Z)");
        assertEquals("10", Base62Encoder.encode(62), "62 rolls over to two digits");
    }

    @Test
    void isMonotonicAndCollisionFreeForASequence() {
        Set<String> seen = new HashSet<>();
        for (long i = 0; i < 5000; i++) {
            String code = Base62Encoder.encode(i);
            assertTrue(seen.add(code), "duplicate code generated for sequential input: " + code + " at i=" + i);
        }
    }

    @Test
    void rejectsNegativeValues() {
        assertThrows(IllegalArgumentException.class, () -> Base62Encoder.encode(-1),
                "negative values are not valid short-code ids");
    }
}

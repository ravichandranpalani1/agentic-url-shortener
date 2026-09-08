package com.schwab.urlshortener.util;

/** Encodes non-negative longs as base62 strings (0-9, A-Z, a-z). */
public final class Base62Encoder {

    private static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final int BASE = ALPHABET.length();

    private Base62Encoder() {
    }

    public static String encode(long value) {
        if (value < 0) {
            throw new IllegalArgumentException("value must be non-negative: " + value);
        }
        if (value == 0) {
            return String.valueOf(ALPHABET.charAt(0));
        }
        StringBuilder sb = new StringBuilder();
        long v = value;
        while (v > 0) {
            int digit = (int) (v % BASE);
            sb.append(ALPHABET.charAt(digit));
            v /= BASE;
        }
        return sb.reverse().toString();
    }
}

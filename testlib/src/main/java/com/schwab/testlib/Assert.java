package com.schwab.testlib;

import java.util.Objects;
import java.util.function.Supplier;

/** Minimal assertion helpers used by the hand-rolled test runner. */
public final class Assert {

    private Assert() {
    }

    public static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    public static void assertFalse(boolean condition, String message) {
        assertTrue(!condition, message);
    }

    public static void assertEquals(Object expected, Object actual, String message) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(message + " -- expected=<" + expected + "> actual=<" + actual + ">");
        }
    }

    public static void assertNotNull(Object value, String message) {
        if (value == null) {
            throw new AssertionError(message);
        }
    }

    public static void assertThrows(Class<? extends Throwable> expected, Runnable body, String message) {
        try {
            body.run();
        } catch (Throwable t) {
            if (expected.isInstance(t)) {
                return;
            }
            throw new AssertionError(message + " -- wrong exception type: " + t.getClass(), t);
        }
        throw new AssertionError(message + " -- expected " + expected.getSimpleName() + " but nothing was thrown");
    }

    public static void fail(String message) {
        throw new AssertionError(message);
    }

    public static <T> void assertEqualsLazy(T expected, Supplier<T> actual, String message) {
        assertEquals(expected, actual.get(), message);
    }
}

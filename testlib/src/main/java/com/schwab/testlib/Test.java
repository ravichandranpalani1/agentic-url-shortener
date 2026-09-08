package com.schwab.testlib;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Minimal test-method marker, modeled after JUnit's @Test.
 *
 * Why hand-rolled: this project is built with zero third-party dependencies
 * (see docs/testing-and-limitations.md for the rationale). A ~120-line
 * reflection-based runner gives us real, executable unit/integration tests
 * without requiring a dependency resolver at build time.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Test {
}

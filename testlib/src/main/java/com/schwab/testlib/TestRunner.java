package com.schwab.testlib;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reflection-based test runner. Scans each class named on the command line
 * for public no-arg methods annotated {@link Test}, runs them, and reports
 * pass/fail. Optionally emits a machine-readable JSON summary so the
 * orchestrator's TestingAgent can parse real results instead of scraping
 * stdout.
 *
 * Usage: java com.schwab.testlib.TestRunner [--json-out=path] Class1 Class2 ...
 */
public final class TestRunner {

    private TestRunner() {
    }

    public static void main(String[] args) throws Exception {
        String jsonOut = null;
        List<String> classNames = new ArrayList<>();
        for (String arg : args) {
            if (arg.startsWith("--json-out=")) {
                jsonOut = arg.substring("--json-out=".length());
            } else {
                classNames.add(arg);
            }
        }

        if (classNames.isEmpty()) {
            System.err.println("No test classes supplied.");
            System.exit(2);
        }

        int total = 0;
        int passed = 0;
        List<String> failures = new ArrayList<>();
        StringBuilder jsonTests = new StringBuilder();
        long suiteStart = System.currentTimeMillis();

        for (String className : classNames) {
            Class<?> clazz = Class.forName(className);
            Object instance = clazz.getDeclaredConstructor().newInstance();
            for (Method m : clazz.getMethods()) {
                if (!m.isAnnotationPresent(Test.class)) {
                    continue;
                }
                total++;
                long start = System.currentTimeMillis();
                boolean ok = true;
                String errorMessage = null;
                try {
                    m.invoke(instance);
                } catch (Throwable t) {
                    ok = false;
                    Throwable cause = t.getCause() != null ? t.getCause() : t;
                    errorMessage = cause.getClass().getSimpleName() + ": " + cause.getMessage();
                }
                long durationMs = System.currentTimeMillis() - start;
                String testId = className + "#" + m.getName();
                if (ok) {
                    passed++;
                    System.out.println("PASS  " + testId + " (" + durationMs + "ms)");
                } else {
                    failures.add(testId + " -- " + errorMessage);
                    System.out.println("FAIL  " + testId + " (" + durationMs + "ms) -- " + errorMessage);
                }
                if (jsonTests.length() > 0) {
                    jsonTests.append(",");
                }
                jsonTests.append("{\"test\":\"").append(escape(testId)).append("\",")
                        .append("\"passed\":").append(ok).append(",")
                        .append("\"durationMs\":").append(durationMs);
                if (errorMessage != null) {
                    jsonTests.append(",\"error\":\"").append(escape(errorMessage)).append("\"");
                }
                jsonTests.append("}");
            }
        }

        long suiteDurationMs = System.currentTimeMillis() - suiteStart;
        int failed = total - passed;

        System.out.println();
        System.out.println("TESTS: total=" + total + " passed=" + passed + " failed=" + failed
                + " durationMs=" + suiteDurationMs);

        if (jsonOut != null) {
            String json = "{\"total\":" + total + ",\"passed\":" + passed + ",\"failed\":" + failed
                    + ",\"durationMs\":" + suiteDurationMs + ",\"tests\":[" + jsonTests + "]}";
            try {
                Path path = Path.of(jsonOut);
                if (path.getParent() != null) {
                    Files.createDirectories(path.getParent());
                }
                Files.writeString(path, json);
            } catch (IOException e) {
                System.err.println("Failed to write JSON summary: " + e.getMessage());
            }
        }

        if (failed > 0) {
            System.exit(1);
        }
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}

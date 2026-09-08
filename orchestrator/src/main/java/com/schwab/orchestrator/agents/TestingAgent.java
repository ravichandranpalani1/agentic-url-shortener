package com.schwab.orchestrator.agents;

import com.schwab.common.json.Json;
import com.schwab.orchestrator.model.StageInput;
import com.schwab.orchestrator.model.StageOutput;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs the project's real, compiled test suite via {@code scripts/test.sh}
 * (javac + the hand-rolled {@code com.schwab.testlib.TestRunner}) and
 * parses its actual JSON summary -- this agent does not simulate test
 * results, it executes them. A non-zero failed count fails the stage,
 * which is what lets the orchestrator's retry/fallback/rollback and
 * release-readiness gating engage with genuine signal.
 */
public final class TestingAgent implements Agent {

    @Override
    public StageOutput execute(StageInput input) throws Exception {
        String scope = String.valueOf(input.params().getOrDefault("scope", "service"));
        Path repoRoot = input.context().repoRoot();
        Path jsonOut = input.context().runDir().resolve("artifacts/test-summary-" + scope + ".json");
        Files.createDirectories(jsonOut.getParent());

        ProcessBuilder pb = new ProcessBuilder("bash", "scripts/test.sh", scope, "--json-out=" + jsonOut)
                .directory(repoRoot.toFile())
                .redirectErrorStream(true);
        Process process = pb.start();

        StringBuilder log = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                log.append(line).append('\n');
            }
        }
        int exitCode = process.waitFor();

        Path logArtifact = input.context().runDir().resolve("artifacts/test-log-" + scope + ".txt");
        Files.writeString(logArtifact, log.toString(), StandardCharsets.UTF_8);

        if (!Files.exists(jsonOut)) {
            throw new IllegalStateException("Test run for scope '" + scope + "' produced no JSON summary "
                    + "(exit code " + exitCode + "); see " + repoRoot.relativize(logArtifact) + " for the raw log");
        }
        Map<String, Object> summary = Json.parseObject(Files.readString(jsonOut));
        long total = ((Number) summary.getOrDefault("total", 0.0)).longValue();
        long passed = ((Number) summary.getOrDefault("passed", 0.0)).longValue();
        long failed = ((Number) summary.getOrDefault("failed", 0.0)).longValue();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("scope", scope);
        data.put("total", total);
        data.put("passed", passed);
        data.put("failed", failed);

        List<String> artifacts = List.of(
                repoRoot.relativize(jsonOut).toString(),
                repoRoot.relativize(logArtifact).toString());

        if (failed > 0 || exitCode != 0) {
            throw new IllegalStateException("Test suite (" + scope + ") failed: " + passed + "/" + total
                    + " passed, " + failed + " failed (exit code " + exitCode + "); see "
                    + repoRoot.relativize(logArtifact));
        }

        return new StageOutput(data, artifacts, "Test suite (" + scope + "): " + passed + "/" + total + " passed.");
    }
}

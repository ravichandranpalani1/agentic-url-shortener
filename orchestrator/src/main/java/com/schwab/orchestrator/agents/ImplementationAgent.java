package com.schwab.orchestrator.agents;

import com.schwab.orchestrator.model.StageInput;
import com.schwab.orchestrator.model.StageOutput;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Applies real changes to the service source tree, or (in "verify-scaffold"
 * mode) confirms the expected greenfield scaffold is present.
 *
 * <p>"apply-change" and "apply-change-with-rollback-demo" modes take a
 * {@code changes} list of {@code {targetFile, sourceAssetPath}} pairs.
 * {@code sourceAssetPath} points at a plain-text file already checked into
 * the repo under {@code scenario-assets/} -- a pre-drafted, reviewed patch
 * -- and this agent copies its content to {@code targetFile}. Copying from
 * a real file (rather than inlining source as an escaped Java string
 * literal in the scenario definition) is what keeps that patch content
 * reviewable and diffable on its own.
 *
 * <p>Two optional, explicitly-labeled simulation params let the scenario
 * definitions exercise the engine's reliability controls deterministically
 * rather than waiting for a real flaky dependency to show up on demand:
 * {@code simulateTransientFailureUntilAttempt} (int) fails every attempt
 * before that attempt number, then proceeds -- demonstrating bounded
 * retries recovering a transient failure class. {@code selfCheckShouldFail}
 * (bool, "apply-change-with-rollback-demo" only) writes the file(s) for
 * real, then fails a post-write validation -- demonstrating the paired
 * rollback agent genuinely deleting what this agent genuinely wrote.
 */
public final class ImplementationAgent implements Agent {

    @Override
    public StageOutput execute(StageInput input) throws Exception {
        Map<String, Object> params = input.params();

        if (params.get("simulateTransientFailureUntilAttempt") instanceof Number n) {
            int recoverAtAttempt = n.intValue();
            if (input.attempt() < recoverAtAttempt) {
                throw new RuntimeException("Simulated transient failure (e.g. a flaky dependency-resolution step) "
                        + "on attempt " + input.attempt() + " of " + recoverAtAttempt);
            }
        }

        String mode = String.valueOf(params.getOrDefault("mode", "verify-scaffold"));
        return switch (mode) {
            case "verify-scaffold" -> verifyScaffold(input);
            case "apply-change" -> applyChanges(input, false);
            case "apply-change-with-rollback-demo" -> applyChanges(input, true);
            default -> throw new IllegalArgumentException("Unknown ImplementationAgent mode: " + mode);
        };
    }

    @SuppressWarnings("unchecked")
    private StageOutput verifyScaffold(StageInput input) throws Exception {
        List<String> expected = (List<String>) input.params().getOrDefault("expectedFiles", List.of());
        Path repoRoot = input.context().repoRoot();
        int found = 0;
        StringBuilder note = new StringBuilder("# Greenfield Scaffold Verification\n\n");
        for (String rel : expected) {
            boolean exists = Files.exists(repoRoot.resolve(rel));
            note.append(exists ? "- [x] " : "- [ ] ").append(rel).append("\n");
            if (exists) {
                found++;
            }
        }
        if (found != expected.size()) {
            throw new IllegalStateException("Greenfield scaffold verification failed: " + found + "/"
                    + expected.size() + " expected files present");
        }

        Path artifact = input.context().runDir().resolve("artifacts/greenfield-scaffold-check.md");
        Files.createDirectories(artifact.getParent());
        Files.writeString(artifact, note.toString(), StandardCharsets.UTF_8);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("filesVerified", found);
        return new StageOutput(data, List.of(repoRoot.relativize(artifact).toString()),
                "Verified " + found + "/" + expected.size() + " expected greenfield scaffold files are present.");
    }

    @SuppressWarnings("unchecked")
    private StageOutput applyChanges(StageInput input, boolean rollbackDemo) throws Exception {
        Map<String, Object> params = input.params();
        List<Map<String, Object>> changes = (List<Map<String, Object>>) (List<?>) params.getOrDefault("changes", List.of());
        Path repoRoot = input.context().repoRoot();
        Path backupDir = input.context().runDir().resolve("backups");
        Files.createDirectories(backupDir);

        List<String> writtenFiles = new ArrayList<>();
        long totalBytes = 0;
        for (Map<String, Object> change : changes) {
            String targetFile = String.valueOf(change.get("targetFile"));
            String sourceAssetPath = String.valueOf(change.get("sourceAssetPath"));
            Path source = repoRoot.resolve(sourceAssetPath);
            Path target = repoRoot.resolve(targetFile);

            // Snapshot the pre-existing content (if any) before overwriting, so a paired rollback
            // agent can restore it rather than blindly deleting a file this stage did not create.
            Path backup = backupDir.resolve(targetFile.replace('/', '_') + ".bak");
            if (Files.exists(target)) {
                Files.copy(target, backup, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }

            String content = Files.readString(source, StandardCharsets.UTF_8);
            Files.createDirectories(target.getParent());
            Files.writeString(target, content, StandardCharsets.UTF_8);
            writtenFiles.add(targetFile);
            totalBytes += content.length();
        }

        if (rollbackDemo && Boolean.TRUE.equals(params.get("selfCheckShouldFail"))) {
            throw new IllegalStateException(
                    "Post-write self-check failed for " + writtenFiles + " (simulated) -- this stage's rollback "
                            + "agent will now revert every file it just wrote");
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("filesChanged", writtenFiles);
        data.put("bytesWritten", totalBytes);
        return new StageOutput(data, writtenFiles, "Applied a real code change to " + writtenFiles.size()
                + " file(s): " + writtenFiles + " (" + totalBytes + " bytes total)");
    }
}

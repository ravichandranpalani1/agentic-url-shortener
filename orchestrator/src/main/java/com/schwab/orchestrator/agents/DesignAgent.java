package com.schwab.orchestrator.agents;

import com.schwab.orchestrator.model.StageInput;
import com.schwab.orchestrator.model.StageOutput;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Codebase reasoning: walks the real service source tree and reports which
 * files textually relate to the requirement's matched domain keywords (from
 * {@link RequirementsAgent}'s output). This is a deliberately simple
 * "impact scan" -- a real system would use symbol/reference analysis or an
 * LLM with repo context -- but it is a genuine filesystem walk over the
 * actual codebase, not a canned answer, so its output changes if the
 * codebase changes (which is exactly what feeds the orchestrator's
 * re-planning/invalidation via the input-hash chain).
 */
public final class DesignAgent implements Agent {

    @Override
    @SuppressWarnings("unchecked")
    public StageOutput execute(StageInput input) throws Exception {
        List<String> keywords = (List<String>) input.context().stageOutput("requirements").data().getOrDefault("keywords", List.of());

        Path serviceSrc = input.context().repoRoot().resolve("service/src/main/java");
        List<String> impacted = new ArrayList<>();
        if (Files.isDirectory(serviceSrc)) {
            List<Path> javaFiles;
            try (Stream<Path> walk = Files.walk(serviceSrc)) {
                javaFiles = walk.filter(p -> p.toString().endsWith(".java")).toList();
            }
            for (Path file : javaFiles) {
                String content = safeRead(file).toLowerCase();
                String fileName = file.getFileName().toString().toLowerCase();
                for (String keyword : keywords) {
                    String needle = keyword.replace(" ", "");
                    if (content.contains(keyword) || fileName.contains(needle)) {
                        impacted.add(input.context().repoRoot().relativize(file).toString());
                        break;
                    }
                }
            }
        }

        java.util.Collections.sort(impacted); // deterministic ordering: this feeds the re-planning input hash

        StringBuilder note = new StringBuilder();
        note.append("# Design / Impact Note\n\n");
        note.append("**Domain keywords considered:** ").append(keywords.isEmpty() ? "(none)" : String.join(", ", keywords)).append("\n\n");
        note.append("**Impacted files (").append(impacted.size()).append("):**\n\n");
        for (String f : impacted) {
            note.append("- `").append(f).append("`\n");
        }
        if (impacted.isEmpty()) {
            note.append("_No existing files matched the requirement's domain keywords -- this looks like new/greenfield surface area._\n");
        }

        Path artifact = input.context().runDir().resolve("artifacts/design-impact-note.md");
        Files.createDirectories(artifact.getParent());
        Files.writeString(artifact, note.toString(), StandardCharsets.UTF_8);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("impactedFiles", impacted);

        String summary = "Scanned " + serviceSrc + " and identified " + impacted.size()
                + " impacted file(s) for " + keywords.size() + " domain keyword(s).";
        return new StageOutput(data, List.of(input.context().repoRoot().relativize(artifact).toString()), summary);
    }

    private static String safeRead(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            return "";
        }
    }
}

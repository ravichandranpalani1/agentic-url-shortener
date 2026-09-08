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
 * Interprets a raw requirement: writes it to disk verbatim for traceability,
 * extracts which parts of the system it touches by matching against a fixed
 * domain vocabulary (a deliberately simple, auditable heuristic rather than
 * an NLP model), and flags likely ambiguity (vague qualifiers with no
 * concrete acceptance criteria). This is what feeds both the design agent's
 * codebase-impact scan and, for the ambiguous scenario, the clarification
 * approval gate.
 */
public final class RequirementsAgent implements Agent {

    private static final List<String> DOMAIN_VOCABULARY = List.of(
            "analytics", "rate limit", "redirect", "alias", "expiration", "expire", "cache",
            "health", "metrics", "click", "short code", "validation", "ssrf", "qr code",
            "dedup", "duplicate", "audit", "reliability", "durability", "wal", "crash");

    private static final List<String> VAGUE_TERMS = List.of(
            "better", "improve", "nicer", "nice", "some", "many", "fast", "faster",
            "reliable", "robust", "good", "enhance", "more", "should probably");

    @Override
    public StageOutput execute(StageInput input) throws Exception {
        String requirementText = String.valueOf(input.params().get("requirementText"));

        Path rawArtifact = input.context().runDir().resolve("artifacts/requirement-raw.txt");
        Files.createDirectories(rawArtifact.getParent());
        Files.writeString(rawArtifact, requirementText, StandardCharsets.UTF_8);

        String lower = requirementText.toLowerCase();
        List<String> keywords = new ArrayList<>();
        for (String term : DOMAIN_VOCABULARY) {
            if (lower.contains(term)) {
                keywords.add(term);
            }
        }

        List<String> vagueFound = new ArrayList<>();
        for (String term : VAGUE_TERMS) {
            if (lower.contains(term)) {
                vagueFound.add(term);
            }
        }
        boolean hasNumber = requirementText.matches("(?s).*\\d+.*");
        boolean hasHardConstraint = lower.matches("(?s).*\\b(must|shall|exactly|within \\d)\\b.*");
        boolean ambiguous = !vagueFound.isEmpty() && !(hasNumber && hasHardConstraint);

        List<String> ambiguityNotes = new ArrayList<>();
        if (ambiguous) {
            ambiguityNotes.add("Requirement uses qualitative terms without measurable acceptance criteria: "
                    + String.join(", ", vagueFound));
            if (!hasNumber) {
                ambiguityNotes.add("No numeric target found (e.g. a latency budget, a retention period, a rate limit)");
            }
        }

        StringBuilder spec = new StringBuilder();
        spec.append("# Normalized Requirement\n\n");
        spec.append("**Raw input:**\n\n> ").append(requirementText.replace("\n", "\n> ")).append("\n\n");
        spec.append("**Matched domain areas:** ").append(keywords.isEmpty() ? "(none matched)" : String.join(", ", keywords)).append("\n\n");
        spec.append("**Ambiguous:** ").append(ambiguous).append("\n\n");
        if (ambiguous) {
            spec.append("**Ambiguity notes:**\n");
            for (String note : ambiguityNotes) {
                spec.append("- ").append(note).append("\n");
            }
        }
        Path specArtifact = input.context().runDir().resolve("artifacts/requirements-spec.md");
        Files.writeString(specArtifact, spec.toString(), StandardCharsets.UTF_8);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("keywords", keywords);
        data.put("ambiguous", ambiguous);
        data.put("ambiguityNotes", ambiguityNotes);

        String summary = ambiguous
                ? "Normalized requirement; flagged as AMBIGUOUS (" + ambiguityNotes.size() + " note(s)); matched "
                        + keywords.size() + " domain area(s)."
                : "Normalized requirement; no ambiguity detected; matched " + keywords.size() + " domain area(s).";

        return new StageOutput(data, List.of(relativize(input.context().repoRoot(), rawArtifact),
                relativize(input.context().repoRoot(), specArtifact)), summary);
    }

    private static String relativize(Path root, Path path) {
        return root.relativize(path).toString();
    }
}

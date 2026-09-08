package com.schwab.orchestrator.agents;

import com.schwab.orchestrator.model.StageInput;
import com.schwab.orchestrator.model.StageOutput;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Records which concrete interpretation of an ambiguous requirement was
 * chosen. This agent doesn't decide anything itself -- the actual judgment
 * call is made by whoever answers this stage's approval gate (see the
 * scenario's approvals file, where the rationale field explains why one
 * interpretation was picked over the alternative). This agent's only job is
 * to package that decision into the workflow context so downstream stages
 * (design, implementation) can act on a concrete spec instead of the raw
 * ambiguous text.
 */
public final class ClarificationAgent implements Agent {

    @Override
    public StageOutput execute(StageInput input) throws Exception {
        String chosenInterpretation = String.valueOf(input.params().get("chosenInterpretation"));
        String rejectedInterpretation = String.valueOf(input.params().getOrDefault("rejectedInterpretation", ""));

        StringBuilder md = new StringBuilder();
        md.append("# Requirement Clarification\n\n");
        md.append("**Chosen interpretation:** ").append(chosenInterpretation).append("\n\n");
        if (!rejectedInterpretation.isBlank()) {
            md.append("**Rejected interpretation:** ").append(rejectedInterpretation).append("\n\n");
        }
        md.append("See this stage's APPROVED entry in the audit log / decision lineage for who decided this and why.\n");

        Path artifact = input.context().runDir().resolve("artifacts/clarification.md");
        Files.createDirectories(artifact.getParent());
        Files.writeString(artifact, md.toString(), StandardCharsets.UTF_8);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("chosenInterpretation", chosenInterpretation);

        return new StageOutput(data, List.of(input.context().repoRoot().relativize(artifact).toString()),
                "Clarified ambiguous requirement: " + chosenInterpretation);
    }
}

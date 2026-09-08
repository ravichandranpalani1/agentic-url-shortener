package com.schwab.orchestrator.agents;

import com.schwab.orchestrator.model.StageInput;
import com.schwab.orchestrator.model.StageOutput;
import com.schwab.orchestrator.model.WorkflowContext;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Final gate: evaluates a real go/no-go checklist against the workflow
 * context. Because this stage only executes once every stage it depends on
 * has already completed (the orchestrator skips stages whose dependencies
 * did not succeed), a NO-GO here signals a defensive check catching
 * something the graph's dependency structure did not -- e.g. a testing
 * stage that "completed" with a non-zero failure count would already have
 * thrown, so this is belt-and-suspenders, not the primary gate.
 */
public final class ReleaseReadinessAgent implements Agent {

    @Override
    public StageOutput execute(StageInput input) throws Exception {
        WorkflowContext ctx = input.context();
        List<String> reasons = new ArrayList<>();
        boolean go = true;

        var testingOutput = ctx.stageOutput("testing");
        if (testingOutput != null) {
            long failed = ((Number) testingOutput.data().getOrDefault("failed", 0L)).longValue();
            if (failed > 0) {
                go = false;
                reasons.add("Testing stage reports " + failed + " failing test(s)");
            } else {
                reasons.add("Testing stage reports 0 failing tests ("
                        + testingOutput.data().getOrDefault("passed", "?") + " passed)");
            }
        } else {
            reasons.add("No testing stage output found in context (unexpected for this graph)");
        }

        long guardrailViolations = ctx.lineage().stream()
                .filter(d -> d.decision().equals("GUARDRAIL_VIOLATION")).count();
        if (guardrailViolations > 0) {
            go = false;
            reasons.add(guardrailViolations + " unresolved guardrail violation(s) recorded in the decision lineage");
        } else {
            reasons.add("No guardrail violations recorded");
        }

        long rejections = ctx.lineage().stream().filter(d -> d.decision().equals("REJECTED")).count();
        if (rejections > 0) {
            go = false;
            reasons.add(rejections + " approval(s) were rejected");
        }

        StringBuilder md = new StringBuilder();
        md.append("# Release Readiness\n\n");
        md.append("**Decision: ").append(go ? "GO" : "NO-GO").append("**\n\n");
        for (String r : reasons) {
            md.append("- ").append(r).append("\n");
        }

        Path artifact = ctx.runDir().resolve("artifacts/release-readiness.md");
        Files.createDirectories(artifact.getParent());
        Files.writeString(artifact, md.toString(), StandardCharsets.UTF_8);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("decision", go ? "GO" : "NO-GO");
        data.put("reasons", reasons);

        if (!go) {
            throw new IllegalStateException("Release readiness check failed: " + String.join("; ", reasons));
        }

        return new StageOutput(data, List.of(ctx.repoRoot().relativize(artifact).toString()),
                "Release readiness: GO (" + reasons.size() + " check(s) passed)");
    }
}

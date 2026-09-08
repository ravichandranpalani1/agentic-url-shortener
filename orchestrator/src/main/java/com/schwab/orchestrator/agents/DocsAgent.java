package com.schwab.orchestrator.agents;

import com.schwab.orchestrator.model.DecisionRecord;
import com.schwab.orchestrator.model.StageInput;
import com.schwab.orchestrator.model.StageOutput;
import com.schwab.orchestrator.model.WorkflowContext;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Generates a run report from the actual workflow context: every completed
 * stage's summary and every recorded decision (approvals, replans,
 * fallbacks, rollbacks). This is real documentation generation driven by
 * what happened in this specific run, not a static template.
 */
public final class DocsAgent implements Agent {

    @Override
    public StageOutput execute(StageInput input) throws Exception {
        WorkflowContext ctx = input.context();
        StringBuilder md = new StringBuilder();
        md.append("# Run Report -- ").append(ctx.scenarioName()).append("\n\n");
        md.append("Generated ").append(Instant.now()).append("\n\n");

        md.append("## Stage Outputs\n\n");
        for (Map.Entry<String, StageOutput> entry : new java.util.TreeMap<>(ctx.allStageOutputs()).entrySet()) {
            md.append("- **").append(entry.getKey()).append("**: ").append(entry.getValue().summary()).append("\n");
        }

        md.append("\n## Decision Lineage\n\n");
        md.append("| time | stage | actor | decision | rationale |\n");
        md.append("|---|---|---|---|---|\n");
        for (DecisionRecord d : ctx.lineage()) {
            md.append("| ").append(Instant.ofEpochMilli(d.timestampMillis()))
                    .append(" | ").append(d.stageId())
                    .append(" | ").append(d.actor())
                    .append(" | ").append(d.decision())
                    .append(" | ").append(d.rationale().replace("|", "\\|")).append(" |\n");
        }

        Path artifact = ctx.runDir().resolve("artifacts/run-report.md");
        Files.createDirectories(artifact.getParent());
        Files.writeString(artifact, md.toString(), StandardCharsets.UTF_8);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("decisionCount", ctx.lineage().size());
        return new StageOutput(data, List.of(ctx.repoRoot().relativize(artifact).toString()),
                "Generated run report with " + ctx.allStageOutputs().size() + " stage summaries and "
                        + ctx.lineage().size() + " lineage entries.");
    }
}

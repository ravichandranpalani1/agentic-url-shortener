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
 * Fallback for {@link NotifyDownstreamAgent}: when the real notification
 * cannot be delivered after bounded retries, degrade gracefully instead of
 * failing the whole run -- record a real artifact that a human/on-call
 * process can pick up, and let the pipeline continue with that noted.
 */
public final class QueueForManualFollowUpAgent implements Agent {

    @Override
    public StageOutput execute(StageInput input) throws Exception {
        Path artifact = input.context().runDir().resolve("artifacts/manual-follow-up-queue.md");
        Files.createDirectories(artifact.getParent());
        String note = "# Manual Follow-up Required\n\n"
                + "The downstream release-notification step could not be completed automatically "
                + "after exhausting its retry budget. Recorded here for manual follow-up instead of "
                + "failing the release.\n";
        Files.writeString(artifact, note, StandardCharsets.UTF_8);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("queued", true);
        return new StageOutput(data, List.of(input.context().repoRoot().relativize(artifact).toString()),
                "Primary notification failed; queued for manual follow-up instead");
    }
}

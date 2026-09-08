package com.schwab.orchestrator.agents;

import com.schwab.orchestrator.model.StageInput;
import com.schwab.orchestrator.model.StageOutput;

import java.util.List;
import java.util.Map;

/**
 * Simulates notifying a downstream system (e.g. a release-channel webhook)
 * as part of release readiness. When {@code params["alwaysFail"] == true}
 * it always fails -- standing in for a downstream dependency that is
 * completely unavailable for the duration of a run, so the stage's bounded
 * retries are guaranteed to be exhausted and its {@link
 * QueueForManualFollowUpAgent} fallback is guaranteed to run. This is a
 * demonstration fixture for the fallback path, explicitly labeled as such
 * in the scenario definition that wires it in.
 */
public final class NotifyDownstreamAgent implements Agent {

    @Override
    public StageOutput execute(StageInput input) throws Exception {
        if (Boolean.TRUE.equals(input.params().get("alwaysFail"))) {
            throw new RuntimeException("Simulated: downstream release-notification endpoint is unavailable");
        }
        return new StageOutput(Map.of("notified", true), List.of(), "Notified downstream release channel");
    }
}

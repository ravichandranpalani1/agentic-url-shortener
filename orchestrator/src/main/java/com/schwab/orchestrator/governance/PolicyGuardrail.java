package com.schwab.orchestrator.governance;

import com.schwab.orchestrator.model.StageInput;
import com.schwab.orchestrator.model.StageOutput;

/**
 * A policy/compliance/change-control check run around every stage
 * execution. {@code checkBefore} runs prior to the agent executing (can
 * block risky actions before they happen); {@code checkAfter} runs once the
 * agent has produced output (can inspect what was actually written, e.g.
 * scanning artifacts for secrets).
 */
public interface PolicyGuardrail {

    String name();

    default void checkBefore(String stageId, StageInput input) throws PolicyViolationException {
        // no-op by default
    }

    default void checkAfter(String stageId, StageInput input, StageOutput output) throws PolicyViolationException {
        // no-op by default
    }
}

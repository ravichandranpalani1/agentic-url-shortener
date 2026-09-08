package com.schwab.orchestrator.agents;

import com.schwab.orchestrator.model.StageInput;
import com.schwab.orchestrator.model.StageOutput;

/**
 * A unit of agentic SDLC work. Each concrete agent performs one real,
 * observable action (parses a requirement, inspects the codebase, applies a
 * patch, runs the real test suite, writes a doc, evaluates a release
 * checklist) rather than simulating one -- see the individual agent
 * implementations for what each actually does.
 */
public interface Agent {
    StageOutput execute(StageInput input) throws Exception;

    default String name() {
        return getClass().getSimpleName();
    }
}

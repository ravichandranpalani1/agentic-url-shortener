package com.schwab.orchestrator.model;

import java.util.Map;

/** What is handed to an agent when its stage executes: its own params plus the shared run context. */
public final class StageInput {
    private final String stageId;
    private final Map<String, Object> params;
    private final WorkflowContext context;
    private final int attempt;

    public StageInput(String stageId, Map<String, Object> params, WorkflowContext context, int attempt) {
        this.stageId = stageId;
        this.params = params == null ? Map.of() : params;
        this.context = context;
        this.attempt = attempt;
    }

    public String stageId() {
        return stageId;
    }

    public Map<String, Object> params() {
        return params;
    }

    public WorkflowContext context() {
        return context;
    }

    /** 1-based attempt number for this execution (2+ means this is a retry). */
    public int attempt() {
        return attempt;
    }
}

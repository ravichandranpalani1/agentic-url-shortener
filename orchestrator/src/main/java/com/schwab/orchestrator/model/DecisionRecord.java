package com.schwab.orchestrator.model;

/** One entry in the workflow's decision lineage -- who decided what, and why, at each stage. */
public record DecisionRecord(long timestampMillis, String stageId, String actor, String decision, String rationale) {

    public static final String ACTOR_AGENT = "AGENT";
    public static final String ACTOR_HUMAN = "HUMAN";
    public static final String ACTOR_SYSTEM = "SYSTEM";
}

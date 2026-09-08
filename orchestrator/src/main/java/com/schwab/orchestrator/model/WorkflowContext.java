package com.schwab.orchestrator.model;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Cross-stage state that survives for the lifetime of one orchestrator run:
 * a shared key/value bag any stage can read or write, every completed
 * stage's output (so downstream stages can reason about upstream results),
 * and an append-only decision lineage -- the "why" behind every gate,
 * retry, approval and rollback, which is what makes the run auditable
 * after the fact rather than just logged.
 */
public final class WorkflowContext {

    private final String scenarioName;
    private final Path repoRoot;
    private final Path runDir;
    private final ConcurrentHashMap<String, Object> shared = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, StageOutput> stageOutputs = new ConcurrentHashMap<>();
    private final List<DecisionRecord> lineage = new CopyOnWriteArrayList<>();

    public WorkflowContext(String scenarioName, Path repoRoot, Path runDir) {
        this.scenarioName = scenarioName;
        this.repoRoot = repoRoot;
        this.runDir = runDir;
    }

    public String scenarioName() {
        return scenarioName;
    }

    public Path repoRoot() {
        return repoRoot;
    }

    public Path runDir() {
        return runDir;
    }

    public void put(String key, Object value) {
        shared.put(key, value);
    }

    public Object get(String key) {
        return shared.get(key);
    }

    public String getString(String key, String defaultValue) {
        Object v = shared.get(key);
        return v == null ? defaultValue : String.valueOf(v);
    }

    public void recordStageOutput(String stageId, StageOutput output) {
        stageOutputs.put(stageId, output);
    }

    public StageOutput stageOutput(String stageId) {
        return stageOutputs.get(stageId);
    }

    public Map<String, StageOutput> allStageOutputs() {
        return Map.copyOf(stageOutputs);
    }

    public void recordDecision(String stageId, String actor, String decision, String rationale) {
        lineage.add(new DecisionRecord(System.currentTimeMillis(), stageId, actor, decision, rationale));
    }

    public List<DecisionRecord> lineage() {
        return List.copyOf(lineage);
    }
}

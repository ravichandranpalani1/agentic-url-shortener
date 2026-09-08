package com.schwab.orchestrator.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** What an agent hands back after executing a stage. */
public final class StageOutput {
    private final Map<String, Object> data;
    private final List<String> artifacts; // file paths written by this stage, relative to the repo root
    private final String summary;

    public StageOutput(Map<String, Object> data, List<String> artifacts, String summary) {
        this.data = data == null ? Map.of() : data;
        this.artifacts = artifacts == null ? List.of() : artifacts;
        this.summary = summary == null ? "" : summary;
    }

    public static StageOutput of(String summary) {
        return new StageOutput(new LinkedHashMap<>(), List.of(), summary);
    }

    public Map<String, Object> data() {
        return data;
    }

    public List<String> artifacts() {
        return artifacts;
    }

    public String summary() {
        return summary;
    }
}

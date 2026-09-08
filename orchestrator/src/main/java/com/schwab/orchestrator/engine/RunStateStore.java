package com.schwab.orchestrator.engine;

import com.schwab.common.json.Json;
import com.schwab.orchestrator.model.StageOutput;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persists each stage's last input hash + status + output to
 * {@code state.json} in the scenario's run directory, and reloads it on the
 * next run. This is the mechanism behind dynamic re-planning: a stage whose
 * inputs are unchanged since the last recorded run is reused instead of
 * re-executed; a stage whose inputs changed (directly, or because an
 * upstream stage it depends on produced different output) is marked stale
 * and re-runs for real.
 */
public final class RunStateStore {

    private final Path stateFile;
    private final Map<String, Object> previous;
    private final Map<String, Map<String, Object>> next = new ConcurrentHashMap<>();

    @SuppressWarnings("unchecked")
    public RunStateStore(Path stateFile) {
        this.stateFile = stateFile;
        if (Files.exists(stateFile)) {
            try {
                this.previous = Json.parseObject(Files.readString(stateFile, StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new RuntimeException("Failed to read prior run state: " + stateFile, e);
            }
        } else {
            this.previous = Map.of();
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> previousFor(String stageId) {
        Object v = previous.get(stageId);
        return v instanceof Map ? (Map<String, Object>) v : null;
    }

    public void record(String stageId, String inputHash, String status, StageOutput output) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("inputHash", inputHash);
        entry.put("status", status);
        if (output != null) {
            entry.put("outputData", output.data());
            entry.put("outputArtifacts", output.artifacts());
            entry.put("outputSummary", output.summary());
        }
        next.put(stageId, entry);
    }

    @SuppressWarnings("unchecked")
    public StageOutput reconstructOutput(Map<String, Object> entry) {
        Map<String, Object> data = entry.get("outputData") instanceof Map ? (Map<String, Object>) entry.get("outputData") : Map.of();
        List<String> artifacts = entry.get("outputArtifacts") instanceof List
                ? ((List<Object>) entry.get("outputArtifacts")).stream().map(String::valueOf).toList() : List.of();
        String summary = String.valueOf(entry.getOrDefault("outputSummary", ""));
        return new StageOutput(new LinkedHashMap<>(data), artifacts, summary);
    }

    public void save() {
        try {
            if (stateFile.getParent() != null) {
                Files.createDirectories(stateFile.getParent());
            }
            Files.writeString(stateFile, Json.write(next), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write run state: " + stateFile, e);
        }
    }
}

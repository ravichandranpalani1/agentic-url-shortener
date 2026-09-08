package com.schwab.orchestrator.graph;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * An explicit dependency graph of {@link Stage}s. {@link #computeBatches()}
 * groups stages into levels where every stage in a level only depends on
 * stages in earlier levels -- stages within one level are independent of
 * each other and safe to run in parallel; levels themselves execute in
 * sequence. This is what gives the engine non-linear, stateful execution
 * instead of a single fixed chain.
 */
public final class WorkflowGraph {

    private final Map<String, Stage> stagesById;

    public WorkflowGraph(List<Stage> stages) {
        Map<String, Stage> byId = new LinkedHashMap<>();
        for (Stage s : stages) {
            if (byId.putIfAbsent(s.id(), s) != null) {
                throw new IllegalArgumentException("Duplicate stage id: " + s.id());
            }
        }
        for (Stage s : stages) {
            for (String dep : s.dependsOn()) {
                if (!byId.containsKey(dep)) {
                    throw new IllegalArgumentException(
                            "Stage '" + s.id() + "' depends on unknown stage '" + dep + "'");
                }
            }
        }
        this.stagesById = Map.copyOf(byId);
        detectCycles();
    }

    public Stage get(String id) {
        return stagesById.get(id);
    }

    public List<Stage> allStages() {
        return List.copyOf(stagesById.values());
    }

    /** Topologically sorted execution levels; stages within a level have no dependency on each other. */
    public List<List<Stage>> computeBatches() {
        Set<String> done = new HashSet<>();
        List<List<Stage>> batches = new ArrayList<>();
        List<Stage> remaining = new ArrayList<>(stagesById.values());

        while (!remaining.isEmpty()) {
            List<Stage> batch = new ArrayList<>();
            for (Stage s : remaining) {
                if (done.containsAll(s.dependsOn())) {
                    batch.add(s);
                }
            }
            if (batch.isEmpty()) {
                // Should be unreachable: detectCycles() already validated the graph at construction time.
                throw new IllegalStateException("Cycle detected while batching stages: " + remaining);
            }
            batches.add(batch);
            for (Stage s : batch) {
                done.add(s.id());
            }
            remaining.removeAll(batch);
        }
        return batches;
    }

    private void detectCycles() {
        Set<String> visiting = new HashSet<>();
        Set<String> visited = new HashSet<>();
        for (String id : stagesById.keySet()) {
            visit(id, visiting, visited);
        }
    }

    private void visit(String id, Set<String> visiting, Set<String> visited) {
        if (visited.contains(id)) {
            return;
        }
        if (!visiting.add(id)) {
            throw new IllegalArgumentException("Cycle detected in workflow graph involving stage '" + id + "'");
        }
        for (String dep : stagesById.get(id).dependsOn()) {
            visit(dep, visiting, visited);
        }
        visiting.remove(id);
        visited.add(id);
    }
}

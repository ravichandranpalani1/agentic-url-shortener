package com.schwab.orchestrator.graph;

import com.schwab.orchestrator.agents.Agent;
import com.schwab.orchestrator.model.StageInput;
import com.schwab.orchestrator.model.StageOutput;
import com.schwab.testlib.Test;

import java.util.List;

import static com.schwab.testlib.Assert.assertEquals;
import static com.schwab.testlib.Assert.assertThrows;
import static com.schwab.testlib.Assert.assertTrue;

public class WorkflowGraphTest {

    private static final Agent NOOP = input -> StageOutput.of("noop");

    @Test
    public void batchesIndependentStagesTogether() {
        Stage a = Stage.builder("a", "A", NOOP).build();
        Stage b = Stage.builder("b", "B", NOOP).build();
        Stage c = Stage.builder("c", "C", NOOP).dependsOn("a", "b").build();
        WorkflowGraph graph = new WorkflowGraph(List.of(a, b, c));

        List<List<Stage>> batches = graph.computeBatches();
        assertEquals(2, batches.size(), "a and b should batch together, c alone in the next batch");
        assertEquals(2, batches.get(0).size(), "first batch should contain both independent stages");
        assertEquals(1, batches.get(1).size(), "second batch should contain only c");
        assertEquals("c", batches.get(1).get(0).id(), "c depends on both a and b");
    }

    @Test
    public void linearChainProducesOneStagePerBatch() {
        Stage a = Stage.builder("a", "A", NOOP).build();
        Stage b = Stage.builder("b", "B", NOOP).dependsOn("a").build();
        Stage c = Stage.builder("c", "C", NOOP).dependsOn("b").build();
        WorkflowGraph graph = new WorkflowGraph(List.of(a, b, c));

        List<List<Stage>> batches = graph.computeBatches();
        assertEquals(3, batches.size(), "a strict chain should produce one batch per stage");
    }

    @Test
    public void rejectsUnknownDependency() {
        Stage a = Stage.builder("a", "A", NOOP).dependsOn("does-not-exist").build();
        assertThrows(IllegalArgumentException.class, () -> new WorkflowGraph(List.of(a)),
                "dependency on an undefined stage id should be rejected at construction");
    }

    @Test
    public void rejectsDuplicateStageIds() {
        Stage a1 = Stage.builder("a", "A1", NOOP).build();
        Stage a2 = Stage.builder("a", "A2", NOOP).build();
        assertThrows(IllegalArgumentException.class, () -> new WorkflowGraph(List.of(a1, a2)),
                "two stages with the same id should be rejected");
    }

    @Test
    public void rejectsCycles() {
        Stage a = Stage.builder("a", "A", NOOP).dependsOn("b").build();
        Stage b = Stage.builder("b", "B", NOOP).dependsOn("a").build();
        assertThrows(IllegalArgumentException.class, () -> new WorkflowGraph(List.of(a, b)),
                "a direct cycle should be rejected at construction");
    }

    @Test
    public void allStagesReturnsEveryRegisteredStage() {
        Stage a = Stage.builder("a", "A", NOOP).build();
        Stage b = Stage.builder("b", "B", NOOP).dependsOn("a").build();
        WorkflowGraph graph = new WorkflowGraph(List.of(a, b));
        assertTrue(graph.allStages().size() == 2, "allStages should return both registered stages");
    }
}

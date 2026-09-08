package com.schwab.orchestrator.engine;

import com.schwab.orchestrator.agents.Agent;
import com.schwab.orchestrator.audit.AuditLogger;
import com.schwab.orchestrator.audit.RunMetrics;
import com.schwab.orchestrator.governance.ApprovalDecision;
import com.schwab.orchestrator.governance.ApprovalGate;
import com.schwab.orchestrator.governance.AutoApproveGate;
import com.schwab.orchestrator.governance.PolicyGuardrail;
import com.schwab.orchestrator.governance.PolicyViolationException;
import com.schwab.orchestrator.graph.Stage;
import com.schwab.orchestrator.graph.WorkflowGraph;
import com.schwab.orchestrator.model.StageInput;
import com.schwab.orchestrator.model.StageOutput;
import com.schwab.orchestrator.model.StageStatus;
import com.schwab.orchestrator.model.WorkflowContext;
import com.schwab.testlib.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static com.schwab.testlib.Assert.assertEquals;
import static com.schwab.testlib.Assert.assertFalse;

public class OrchestratorTest {

    private Path newRepoRoot() throws Exception {
        return Files.createTempDirectory("orch-test-");
    }

    private Orchestrator newOrchestrator(WorkflowGraph graph, WorkflowContext ctx, ApprovalGate gate) {
        AuditLogger audit = new AuditLogger(ctx.runDir().resolve("audit.jsonl"), "test-trace");
        RunMetrics metrics = new RunMetrics();
        RunStateStore stateStore = new RunStateStore(ctx.runDir().resolve("state.json"));
        return new Orchestrator(graph, ctx, gate, audit, metrics, stateStore);
    }

    @Test
    public void happyPathCompletesEveryIndependentAndChainedStage() throws Exception {
        Path root = newRepoRoot();
        WorkflowContext ctx = new WorkflowContext("t", root, root.resolve("run"));
        Stage a = Stage.builder("a", "A", ok()).build();
        Stage b = Stage.builder("b", "B", ok()).dependsOn("a").build();
        WorkflowGraph graph = new WorkflowGraph(List.of(a, b));

        Map<String, StageStatus> statuses = newOrchestrator(graph, ctx, new AutoApproveGate()).run();

        assertEquals(StageStatus.COMPLETED, statuses.get("a"), "a should complete");
        assertEquals(StageStatus.COMPLETED, statuses.get("b"), "b should complete");
    }

    @Test
    public void retryRecoversATransientFailure() throws Exception {
        Path root = newRepoRoot();
        WorkflowContext ctx = new WorkflowContext("t", root, root.resolve("run"));
        AtomicInteger calls = new AtomicInteger();
        Agent flaky = input -> {
            if (calls.incrementAndGet() == 1) {
                throw new RuntimeException("simulated transient failure");
            }
            return StageOutput.of("recovered");
        };
        Stage stage = Stage.builder("flaky", "Flaky", flaky).maxRetries(3).retryBackoffMillis(10).build();
        WorkflowGraph graph = new WorkflowGraph(List.of(stage));

        Map<String, StageStatus> statuses = newOrchestrator(graph, ctx, new AutoApproveGate()).run();

        assertEquals(StageStatus.COMPLETED, statuses.get("flaky"), "stage should complete after one retry");
        assertEquals(2, calls.get(), "agent should have been invoked exactly twice (1 failure + 1 success)");
    }

    @Test
    public void fallbackRunsAfterRetriesAreExhausted() throws Exception {
        Path root = newRepoRoot();
        WorkflowContext ctx = new WorkflowContext("t", root, root.resolve("run"));
        Agent alwaysFails = input -> {
            throw new RuntimeException("simulated permanent failure");
        };
        Agent fallback = input -> StageOutput.of("degraded");
        Stage stage = Stage.builder("primary", "Primary", alwaysFails)
                .maxRetries(2).retryBackoffMillis(5).fallback(fallback).build();
        Stage dependent = Stage.builder("dependent", "Dependent", ok()).dependsOn("primary").build();
        WorkflowGraph graph = new WorkflowGraph(List.of(stage, dependent));

        Map<String, StageStatus> statuses = newOrchestrator(graph, ctx, new AutoApproveGate()).run();

        assertEquals(StageStatus.COMPLETED, statuses.get("primary"), "stage should complete via its fallback");
        assertEquals(StageStatus.COMPLETED, statuses.get("dependent"),
                "a stage depending on a fallback-completed stage should still run");
    }

    @Test
    public void rollbackDeletesWhatTheFailedStageWrote() throws Exception {
        Path root = newRepoRoot();
        WorkflowContext ctx = new WorkflowContext("t", root, root.resolve("run"));
        Path written = root.resolve("scratch.txt");

        Agent writesThenFails = input -> {
            Files.writeString(written, "partial write");
            throw new RuntimeException("post-write validation failed");
        };
        Agent rollback = input -> {
            Files.deleteIfExists(written);
            return StageOutput.of("reverted");
        };
        Stage stage = Stage.builder("risky", "Risky", writesThenFails).maxRetries(1).rollback(rollback).build();
        WorkflowGraph graph = new WorkflowGraph(List.of(stage));

        newOrchestrator(graph, ctx, new AutoApproveGate()).run();

        assertFalse(Files.exists(written), "rollback agent should have deleted the file the primary agent wrote");
    }

    @Test
    public void dependentStageIsSkippedWhenUpstreamFails() throws Exception {
        Path root = newRepoRoot();
        WorkflowContext ctx = new WorkflowContext("t", root, root.resolve("run"));
        Agent alwaysFails = input -> {
            throw new RuntimeException("permanent failure, no fallback/rollback configured");
        };
        Stage upstream = Stage.builder("upstream", "Upstream", alwaysFails).maxRetries(1).build();
        Stage downstream = Stage.builder("downstream", "Downstream", ok()).dependsOn("upstream").build();
        WorkflowGraph graph = new WorkflowGraph(List.of(upstream, downstream));

        Map<String, StageStatus> statuses = newOrchestrator(graph, ctx, new AutoApproveGate()).run();

        assertEquals(StageStatus.FAILED, statuses.get("upstream"), "upstream should be FAILED");
        assertEquals(StageStatus.SKIPPED, statuses.get("downstream"), "downstream should be SKIPPED, not run");
    }

    @Test
    public void approvalRejectionFailsTheStage() throws Exception {
        Path root = newRepoRoot();
        WorkflowContext ctx = new WorkflowContext("t", root, root.resolve("run"));
        Stage stage = Stage.builder("gated", "Gated", ok()).requiresApproval("high impact").build();
        WorkflowGraph graph = new WorkflowGraph(List.of(stage));
        ApprovalGate rejectAll = (stageId, reason, input) ->
                new ApprovalDecision(false, "reviewer", "not ready", System.currentTimeMillis(), false);

        Map<String, StageStatus> statuses = newOrchestrator(graph, ctx, rejectAll).run();

        assertEquals(StageStatus.FAILED, statuses.get("gated"), "a rejected approval should fail the stage");
    }

    @Test
    public void safeStopSkipsAnUnrelatedStageInALaterBatch() throws Exception {
        Path root = newRepoRoot();
        WorkflowContext ctx = new WorkflowContext("t", root, root.resolve("run"));
        PolicyGuardrail criticalGuardrail = new PolicyGuardrail() {
            @Override
            public String name() {
                return "critical";
            }

            @Override
            public void checkAfter(String stageId, StageInput input, StageOutput output) throws PolicyViolationException {
                throw new PolicyViolationException(name(), "critical violation", true);
            }
        };
        Stage first = Stage.builder("first", "First", ok()).guardrail(criticalGuardrail).build();
        // "laterUnrelated" depends on nothing, but the engine still batches by topological level;
        // give it a dependency on a third, unrelated stage in the same batch as "first" so it is
        // forced into batch 2 regardless of dependency direction relative to "first" itself.
        Stage anchor = Stage.builder("anchor", "Anchor", ok()).build();
        Stage laterUnrelated = Stage.builder("laterUnrelated", "Later", ok()).dependsOn("anchor").build();
        WorkflowGraph graph = new WorkflowGraph(List.of(first, anchor, laterUnrelated));

        Map<String, StageStatus> statuses = newOrchestrator(graph, ctx, new AutoApproveGate()).run();

        assertEquals(StageStatus.FAILED, statuses.get("first"), "first should fail its critical guardrail");
        assertEquals(StageStatus.SKIPPED, statuses.get("laterUnrelated"),
                "a stage in a later batch must be skipped once safe-stop has tripped, even with no direct dependency on the failed stage");
    }

    @Test
    public void unchangedInputIsReusedOnASecondRun() throws Exception {
        Path root = newRepoRoot();
        Path runDir = root.resolve("run");
        AtomicInteger executions = new AtomicInteger();
        Agent counting = input -> {
            executions.incrementAndGet();
            return new StageOutput(Map.of("count", 42), List.of(), "ran");
        };
        Stage stage = Stage.builder("s", "S", counting).build();
        WorkflowGraph graph = new WorkflowGraph(List.of(stage));

        WorkflowContext ctx1 = new WorkflowContext("t", root, runDir);
        newOrchestrator(graph, ctx1, new AutoApproveGate()).run();
        assertEquals(1, executions.get(), "first run should execute the agent once");

        WorkflowContext ctx2 = new WorkflowContext("t", root, runDir);
        Map<String, StageStatus> secondRunStatuses = newOrchestrator(graph, ctx2, new AutoApproveGate()).run();
        assertEquals(1, executions.get(), "second run with unchanged params should NOT re-invoke the agent");
        assertEquals(StageStatus.REUSED, secondRunStatuses.get("s"), "second run should report REUSED");
    }

    @Test
    public void changedInputCausesStaleReExecutionAndCascadesToDependents() throws Exception {
        Path root = newRepoRoot();
        Path runDir = root.resolve("run");
        AtomicInteger upstreamExecutions = new AtomicInteger();
        AtomicInteger downstreamExecutions = new AtomicInteger();

        // params()'s value is captured per WorkflowGraph build below, so we build two graphs with
        // different upstream params to simulate a requirement changing between runs.
        Agent upstreamAgent = input -> {
            upstreamExecutions.incrementAndGet();
            return new StageOutput(Map.of("value", input.params().get("v")), List.of(), "ran");
        };
        Agent downstreamAgent = input -> {
            downstreamExecutions.incrementAndGet();
            return StageOutput.of("ran");
        };

        WorkflowGraph graphV1 = new WorkflowGraph(List.of(
                Stage.builder("upstream", "Upstream", upstreamAgent).param("v", "one").build(),
                Stage.builder("downstream", "Downstream", downstreamAgent).dependsOn("upstream").build()));
        WorkflowContext ctx1 = new WorkflowContext("t", root, runDir);
        newOrchestrator(graphV1, ctx1, new AutoApproveGate()).run();
        assertEquals(1, upstreamExecutions.get(), "first run should execute upstream once");
        assertEquals(1, downstreamExecutions.get(), "first run should execute downstream once");

        // Same graph, unchanged param -- everything should be reused.
        WorkflowGraph graphV1Again = new WorkflowGraph(List.of(
                Stage.builder("upstream", "Upstream", upstreamAgent).param("v", "one").build(),
                Stage.builder("downstream", "Downstream", downstreamAgent).dependsOn("upstream").build()));
        WorkflowContext ctx2 = new WorkflowContext("t", root, runDir);
        newOrchestrator(graphV1Again, ctx2, new AutoApproveGate()).run();
        assertEquals(1, upstreamExecutions.get(), "unchanged param should not re-invoke upstream");
        assertEquals(1, downstreamExecutions.get(), "unchanged upstream output should not re-invoke downstream either");

        // Now change the upstream param -- upstream must re-execute, and because its output changes,
        // downstream's input hash (which includes upstream's output data) must change too, cascading
        // the staleness even though downstream's own params never changed.
        WorkflowGraph graphV2 = new WorkflowGraph(List.of(
                Stage.builder("upstream", "Upstream", upstreamAgent).param("v", "two").build(),
                Stage.builder("downstream", "Downstream", downstreamAgent).dependsOn("upstream").build()));
        WorkflowContext ctx3 = new WorkflowContext("t", root, runDir);
        Map<String, StageStatus> statuses = newOrchestrator(graphV2, ctx3, new AutoApproveGate()).run();

        assertEquals(2, upstreamExecutions.get(), "changed param should cause upstream to re-execute");
        assertEquals(2, downstreamExecutions.get(),
                "upstream's changed output should cascade staleness to downstream even though downstream's own params are unchanged");
        assertEquals(StageStatus.COMPLETED, statuses.get("upstream"), "upstream should report COMPLETED after re-executing");
        assertEquals(StageStatus.COMPLETED, statuses.get("downstream"), "downstream should report COMPLETED after re-executing");
    }

    private static Agent ok() {
        return input -> StageOutput.of("ok");
    }
}

package com.schwab.orchestrator.engine;

import com.schwab.common.json.Json;
import com.schwab.orchestrator.audit.AuditLogger;
import com.schwab.orchestrator.audit.RunMetrics;
import com.schwab.orchestrator.governance.ApprovalDecision;
import com.schwab.orchestrator.governance.ApprovalGate;
import com.schwab.orchestrator.governance.PolicyGuardrail;
import com.schwab.orchestrator.governance.PolicyViolationException;
import com.schwab.orchestrator.graph.Stage;
import com.schwab.orchestrator.graph.WorkflowGraph;
import com.schwab.orchestrator.model.DecisionRecord;
import com.schwab.orchestrator.model.StageInput;
import com.schwab.orchestrator.model.StageOutput;
import com.schwab.orchestrator.model.StageStatus;
import com.schwab.orchestrator.model.WorkflowContext;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Executes a {@link WorkflowGraph}: batches of mutually-independent stages
 * run in parallel, batches themselves run in sequence with a full
 * synchronization barrier between them (every stage in batch N finishes --
 * successfully, failed, or skipped -- before batch N+1 starts, so a stage
 * never reads a dependency's output while it is still being written). Each
 * stage execution goes through, in order: dependency/skip check, dynamic
 * re-planning (reuse vs. re-execute based on an input-content hash),
 * pre-execution policy guardrails, an optional human approval gate, a
 * bounded retry loop with backoff, fallback, rollback, post-execution
 * guardrails, and audit logging + metrics at every step. A guardrail
 * violation flagged {@code safeStop} halts the remainder of the run.
 */
public final class Orchestrator {

    private final WorkflowGraph graph;
    private final WorkflowContext context;
    private final ApprovalGate approvalGate;
    private final AuditLogger audit;
    private final RunMetrics metrics;
    private final RunStateStore stateStore;
    private final ExecutorService executor;
    private final AtomicBoolean safeStopTriggered = new AtomicBoolean(false);
    private final Map<String, StageStatus> statuses = new ConcurrentHashMap<>();

    public Orchestrator(WorkflowGraph graph, WorkflowContext context, ApprovalGate approvalGate,
                         AuditLogger audit, RunMetrics metrics, RunStateStore stateStore) {
        this.graph = graph;
        this.context = context;
        this.approvalGate = approvalGate;
        this.audit = audit;
        this.metrics = metrics;
        this.stateStore = stateStore;
        this.executor = Executors.newFixedThreadPool(Math.max(2, Runtime.getRuntime().availableProcessors()));
    }

    /** Runs the whole graph to completion (or to safe-stop) and returns the final status of every stage. */
    public Map<String, StageStatus> run() {
        for (Stage s : graph.allStages()) {
            metrics.stageRegistered();
        }

        List<List<Stage>> batches = graph.computeBatches();
        for (int i = 0; i < batches.size(); i++) {
            List<Stage> batch = batches.get(i);
            if (safeStopTriggered.get()) {
                for (Stage s : batch) {
                    markSkipped(s, "safe-stop was triggered by an earlier stage");
                }
                continue;
            }
            audit.log("orchestrator", "BATCH_STARTED", i + 1,
                    Map.of("stages", batch.stream().map(Stage::id).toList()));
            List<Future<?>> futures = new ArrayList<>();
            for (Stage s : batch) {
                futures.add(executor.submit(() -> executeStage(s)));
            }
            // Synchronization barrier: every stage in this batch must finish before the next batch starts.
            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (Exception e) {
                    audit.log("orchestrator", "INTERNAL_ERROR", 1, Map.of("message", String.valueOf(e)));
                }
            }
        }

        metrics.finish();
        stateStore.save();
        executor.shutdown();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        return Map.copyOf(statuses);
    }

    public Map<String, StageStatus> statuses() {
        return Map.copyOf(statuses);
    }

    private void executeStage(Stage stage) {
        for (String dep : stage.dependsOn()) {
            StageStatus depStatus = statuses.get(dep);
            if (depStatus != StageStatus.COMPLETED && depStatus != StageStatus.REUSED) {
                markSkipped(stage, "dependency '" + dep + "' did not complete (status=" + depStatus + ")");
                return;
            }
        }
        if (safeStopTriggered.get()) {
            markSkipped(stage, "safe-stop triggered before this stage started");
            return;
        }

        statuses.put(stage.id(), StageStatus.RUNNING);
        audit.log(stage.id(), "STARTED", 1, Map.of("name", stage.name()));

        String inputHash = computeInputHash(stage);
        Map<String, Object> prev = stateStore.previousFor(stage.id());
        if (prev != null && inputHash.equals(prev.get("inputHash")) && "COMPLETED".equals(prev.get("status"))) {
            StageOutput reused = stateStore.reconstructOutput(prev);
            context.recordStageOutput(stage.id(), reused);
            statuses.put(stage.id(), StageStatus.REUSED);
            metrics.stageReused();
            stateStore.record(stage.id(), inputHash, "REUSED", reused);
            audit.log(stage.id(), "REUSED", 1, Map.of("inputHash", inputHash,
                    "reason", "input unchanged since previous run; skipping re-execution"));
            context.recordDecision(stage.id(), DecisionRecord.ACTOR_SYSTEM, "REUSE",
                    "Input hash unchanged since previous run; carried forward the previous completed output");
            return;
        }
        if (prev != null && !inputHash.equals(prev.get("inputHash"))) {
            audit.log(stage.id(), "STALE", 1, Map.of(
                    "previousInputHash", prev.get("inputHash"), "newInputHash", inputHash));
            context.recordDecision(stage.id(), DecisionRecord.ACTOR_SYSTEM, "REPLAN",
                    "Upstream input changed since the previous run; stage marked stale and will re-execute");
        }

        StageInput preInput = new StageInput(stage.id(), stage.params(), context, 1);
        for (PolicyGuardrail g : stage.guardrails()) {
            try {
                g.checkBefore(stage.id(), preInput);
            } catch (PolicyViolationException e) {
                handleGuardrailViolation(stage, g, e);
                return;
            }
        }

        if (stage.requiresApproval()) {
            statuses.put(stage.id(), StageStatus.BLOCKED_ON_APPROVAL);
            metrics.approvalRequested();
            audit.log(stage.id(), "APPROVAL_REQUESTED", 1, Map.of("reason", stage.approvalReason()));
            ApprovalDecision decision = approvalGate.requestApproval(stage.id(), stage.approvalReason(), preInput);
            audit.log(stage.id(), decision.approved() ? "APPROVED" : "REJECTED", 1, Map.of(
                    "approver", decision.approver(), "rationale", decision.rationale(), "simulated", decision.simulated()));
            context.recordDecision(stage.id(), decision.simulated() ? DecisionRecord.ACTOR_SYSTEM : DecisionRecord.ACTOR_HUMAN,
                    decision.approved() ? "APPROVED" : "REJECTED", decision.rationale());
            if (!decision.approved()) {
                metrics.approvalRejected();
                markFailed(stage, inputHash, "Rejected by approval gate: " + decision.rationale());
                return;
            }
        }

        StageOutput output = null;
        Exception lastError = null;
        int attempts = Math.max(1, stage.maxRetries());
        for (int attempt = 1; attempt <= attempts; attempt++) {
            if (attempt > 1) {
                metrics.retryAttempted(stage.id());
                statuses.put(stage.id(), StageStatus.RETRYING);
                audit.log(stage.id(), "RETRY", attempt, Map.of("previousError", String.valueOf(lastError)));
                sleep(stage.retryBackoffMillis() * attempt);
            }
            try {
                output = stage.agent().execute(new StageInput(stage.id(), stage.params(), context, attempt));
                lastError = null;
                break;
            } catch (Exception e) {
                lastError = e;
                audit.log(stage.id(), "ATTEMPT_FAILED", attempt, Map.of("message", String.valueOf(e.getMessage())));
            }
        }

        if (output == null && stage.fallbackAgent() != null) {
            audit.log(stage.id(), "FALLBACK_INVOKED", attempts, Map.of());
            try {
                output = stage.fallbackAgent().execute(new StageInput(stage.id(), stage.params(), context, attempts));
                context.recordDecision(stage.id(), DecisionRecord.ACTOR_SYSTEM, "FALLBACK",
                        "Primary agent failed after " + attempts + " attempt(s); fallback agent succeeded");
            } catch (Exception fe) {
                lastError = fe;
                audit.log(stage.id(), "FALLBACK_FAILED", attempts, Map.of("message", String.valueOf(fe.getMessage())));
            }
        }

        if (output == null) {
            if (stage.rollbackAgent() != null) {
                try {
                    stage.rollbackAgent().execute(new StageInput(stage.id(), stage.params(), context, attempts));
                    metrics.rollbackPerformed();
                    statuses.put(stage.id(), StageStatus.ROLLED_BACK);
                    audit.log(stage.id(), "ROLLED_BACK", attempts, Map.of());
                    context.recordDecision(stage.id(), DecisionRecord.ACTOR_SYSTEM, "ROLLBACK",
                            "Reverted side effects of stage " + stage.id() + " after exhausting retries/fallback");
                } catch (Exception re) {
                    audit.log(stage.id(), "ROLLBACK_FAILED", attempts, Map.of("message", String.valueOf(re.getMessage())));
                }
            }
            markFailed(stage, inputHash, "Agent failed after " + attempts + " attempt(s): " + lastError);
            return;
        }

        // Canonicalize the output's data through the same JSON round-trip used by RunStateStore
        // before it is ever read (for hashing or by downstream stages). Without this, a freshly
        // executed stage's output (native Java types, e.g. an Integer) and a REUSED stage's output
        // (reconstructed from JSON, where every number decodes as a Double) serialize differently
        // even when semantically identical -- which made computeInputHash() unstable across runs
        // and caused unrelated downstream stages to be spuriously marked STALE. Found by actually
        // running the greenfield scenario twice in a row and inspecting the second run's statuses;
        // see docs/testing-and-limitations.md.
        output = new StageOutput(Json.parseObject(Json.write(output.data())), output.artifacts(), output.summary());

        StageInput finalInput = new StageInput(stage.id(), stage.params(), context, attempts);
        for (PolicyGuardrail g : stage.guardrails()) {
            try {
                g.checkAfter(stage.id(), finalInput, output);
            } catch (PolicyViolationException e) {
                handleGuardrailViolation(stage, g, e);
                return;
            }
        }

        context.recordStageOutput(stage.id(), output);
        statuses.put(stage.id(), StageStatus.COMPLETED);
        metrics.stageCompleted(stage.id());
        stateStore.record(stage.id(), inputHash, "COMPLETED", output);
        audit.log(stage.id(), "COMPLETED", attempts, Map.of("summary", output.summary()));
        context.recordDecision(stage.id(), DecisionRecord.ACTOR_AGENT, "COMPLETED", output.summary());
    }

    private void handleGuardrailViolation(Stage stage, PolicyGuardrail guardrail, PolicyViolationException e) {
        audit.log(stage.id(), "GUARDRAIL_VIOLATION", 1, Map.of(
                "guardrail", guardrail.name(), "message", e.getMessage(), "safeStop", e.safeStop));
        context.recordDecision(stage.id(), DecisionRecord.ACTOR_SYSTEM, "GUARDRAIL_VIOLATION",
                guardrail.name() + ": " + e.getMessage());
        markFailed(stage, computeInputHash(stage), "Guardrail '" + guardrail.name() + "' violated: " + e.getMessage());
        if (e.safeStop) {
            safeStopTriggered.set(true);
            audit.log("orchestrator", "SAFE_STOP", 1, Map.of("triggeredByStage", stage.id(), "guardrail", guardrail.name()));
            context.recordDecision("orchestrator", DecisionRecord.ACTOR_SYSTEM, "SAFE_STOP",
                    "Critical guardrail violation in stage " + stage.id() + "; halting the remainder of the run");
        }
    }

    private void markFailed(Stage stage, String inputHash, String reason) {
        statuses.put(stage.id(), StageStatus.FAILED);
        metrics.stageFailed();
        stateStore.record(stage.id(), inputHash, "FAILED", null);
        audit.log(stage.id(), "FAILED", 1, Map.of("reason", reason));
    }

    private void markSkipped(Stage stage, String reason) {
        statuses.put(stage.id(), StageStatus.SKIPPED);
        metrics.stageSkipped();
        audit.log(stage.id(), "SKIPPED", 1, Map.of("reason", reason));
    }

    /**
     * A stage's input hash covers its static params plus the (sorted, so
     * order-independent) output data of every stage it depends on. This
     * means an upstream change automatically invalidates every downstream
     * stage that transitively depends on it, without the engine needing
     * any stage-specific invalidation rules.
     */
    private String computeInputHash(Stage stage) {
        TreeMap<String, Object> depOutputs = new TreeMap<>();
        for (String dep : stage.dependsOn()) {
            StageOutput out = context.stageOutput(dep);
            depOutputs.put(dep, out == null ? null : out.data());
        }
        String material = Json.write(new TreeMap<>(stage.params())) + "|" + Json.write(depOutputs);
        return sha256(material);
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

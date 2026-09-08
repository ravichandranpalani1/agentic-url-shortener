package com.schwab.orchestrator.cli;

import com.schwab.orchestrator.audit.AuditLogger;
import com.schwab.orchestrator.audit.RunMetrics;
import com.schwab.orchestrator.engine.Orchestrator;
import com.schwab.orchestrator.engine.RunStateStore;
import com.schwab.orchestrator.governance.ApprovalGate;
import com.schwab.orchestrator.governance.AutoApproveGate;
import com.schwab.orchestrator.governance.ConsoleApprovalGate;
import com.schwab.orchestrator.governance.FileBackedApprovalGate;
import com.schwab.orchestrator.graph.WorkflowGraph;
import com.schwab.orchestrator.model.StageStatus;
import com.schwab.orchestrator.model.WorkflowContext;
import com.schwab.orchestrator.scenarios.ScenarioDefinitions;

import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * CLI entry point: {@code java com.schwab.orchestrator.cli.Main <scenario> [options]}.
 *
 * <p>Options:
 * <ul>
 *   <li>{@code --approvals=<path>} -- JSON approvals file (default: approvals/&lt;scenario&gt;.json).
 *       Omit to use {@link ConsoleApprovalGate} (interactive) instead.</li>
 *   <li>{@code --auto} -- use {@link AutoApproveGate} instead (approves everything, clearly
 *       flagged as simulated in the audit log; for smoke-testing only).</li>
 *   <li>{@code --repo-root=<path>} -- repo root (default: current working directory).</li>
 * </ul>
 *
 * <p>Exit code is 0 if the release-readiness stage completed with a GO decision, 1 otherwise.
 */
public final class Main {

    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Usage: java com.schwab.orchestrator.cli.Main <greenfield|brownfield|ambiguous> "
                    + "[--approvals=<path>] [--auto] [--repo-root=<path>]");
            System.exit(2);
        }

        String scenario = args[0];
        String approvalsArg = null;
        boolean auto = false;
        Path repoRoot = Path.of(System.getProperty("user.dir"));

        for (int i = 1; i < args.length; i++) {
            String arg = args[i];
            if (arg.startsWith("--approvals=")) {
                approvalsArg = arg.substring("--approvals=".length());
            } else if (arg.equals("--auto")) {
                auto = true;
            } else if (arg.startsWith("--repo-root=")) {
                repoRoot = Path.of(arg.substring("--repo-root=".length()));
            } else {
                System.err.println("Unknown argument: " + arg);
                System.exit(2);
            }
        }

        Path runDir = repoRoot.resolve("runs").resolve(scenario);
        WorkflowGraph graph = ScenarioDefinitions.forName(scenario);
        WorkflowContext context = new WorkflowContext(scenario, repoRoot, runDir);

        ApprovalGate approvalGate;
        if (auto) {
            approvalGate = new AutoApproveGate();
        } else if (approvalsArg != null) {
            approvalGate = new FileBackedApprovalGate(Path.of(approvalsArg));
        } else {
            Path defaultApprovals = repoRoot.resolve("approvals").resolve(scenario + ".json");
            approvalGate = java.nio.file.Files.exists(defaultApprovals)
                    ? new FileBackedApprovalGate(defaultApprovals)
                    : new ConsoleApprovalGate();
        }

        String traceId = UUID.randomUUID().toString();
        AuditLogger audit = new AuditLogger(runDir.resolve("audit.jsonl"), traceId);
        RunMetrics metrics = new RunMetrics();
        RunStateStore stateStore = new RunStateStore(runDir.resolve("state.json"));

        System.out.println("=== Orchestrator run: scenario=" + scenario + " traceId=" + traceId + " ===");

        Orchestrator orchestrator = new Orchestrator(graph, context, approvalGate, audit, metrics, stateStore);
        Map<String, StageStatus> statuses = orchestrator.run();

        System.out.println();
        System.out.println("=== FINAL STAGE STATUSES ===");
        for (Map.Entry<String, StageStatus> e : new TreeMap<>(statuses).entrySet()) {
            System.out.println("  " + e.getKey() + " = " + e.getValue());
        }
        metrics.printSummary();

        StageStatus releaseStatus = statuses.get("release");
        boolean go = releaseStatus == StageStatus.COMPLETED || releaseStatus == StageStatus.REUSED;
        System.out.println();
        System.out.println("=== RESULT: " + (go ? "GO" : "NO-GO") + " ===");
        System.out.println("Audit log: " + runDir.resolve("audit.jsonl"));
        System.out.println("Run state: " + runDir.resolve("state.json"));

        System.exit(go ? 0 : 1);
    }
}

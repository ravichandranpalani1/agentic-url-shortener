package com.schwab.orchestrator.audit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Reliability metrics for one orchestrator run: success rate, retry and
 * rollback frequency, mean time to recovery (MTTR) for stages that failed
 * and then succeeded on a later attempt, and end-to-end latency. These are
 * the numbers the assessment's governance requirements ask for, computed
 * from real stage executions rather than hard-coded.
 */
public final class RunMetrics {

    private final long runStartMillis = System.currentTimeMillis();
    private volatile long runEndMillis;

    private final AtomicInteger totalStages = new AtomicInteger();
    private final AtomicInteger completedStages = new AtomicInteger();
    private final AtomicInteger failedStages = new AtomicInteger();
    private final AtomicInteger skippedStages = new AtomicInteger();
    private final AtomicInteger reusedStages = new AtomicInteger();
    private final AtomicInteger totalRetries = new AtomicInteger();
    private final AtomicInteger totalRollbacks = new AtomicInteger();
    private final AtomicInteger approvalsRequested = new AtomicInteger();
    private final AtomicInteger approvalsRejected = new AtomicInteger();

    private final ConcurrentHashMap<String, Long> firstFailureAtMillis = new ConcurrentHashMap<>();
    private final List<Long> recoveryDurationsMillis = Collections.synchronizedList(new ArrayList<>());

    public void stageRegistered() {
        totalStages.incrementAndGet();
    }

    public void stageCompleted(String stageId) {
        completedStages.incrementAndGet();
        Long firstFail = firstFailureAtMillis.remove(stageId);
        if (firstFail != null) {
            recoveryDurationsMillis.add(System.currentTimeMillis() - firstFail);
        }
    }

    public void stageFailed() {
        failedStages.incrementAndGet();
    }

    public void stageSkipped() {
        skippedStages.incrementAndGet();
    }

    public void stageReused() {
        reusedStages.incrementAndGet();
    }

    public void retryAttempted(String stageId) {
        totalRetries.incrementAndGet();
        firstFailureAtMillis.putIfAbsent(stageId, System.currentTimeMillis());
    }

    public void rollbackPerformed() {
        totalRollbacks.incrementAndGet();
    }

    public void approvalRequested() {
        approvalsRequested.incrementAndGet();
    }

    public void approvalRejected() {
        approvalsRejected.incrementAndGet();
    }

    public void finish() {
        runEndMillis = System.currentTimeMillis();
    }

    public Map<String, Object> snapshot() {
        int total = totalStages.get();
        // REUSED is a successful outcome via the fast (re-planning) path, not a failure -- a fully
        // REUSED rerun of an unchanged pipeline is a 100% success, not 0%.
        double successRate = total == 0 ? 0.0 : (double) (completedStages.get() + reusedStages.get()) / total;
        double mttrMillis = recoveryDurationsMillis.isEmpty() ? 0.0
                : recoveryDurationsMillis.stream().mapToLong(Long::longValue).average().orElse(0.0);
        long endToEnd = (runEndMillis == 0 ? System.currentTimeMillis() : runEndMillis) - runStartMillis;

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("totalStages", total);
        m.put("completedStages", completedStages.get());
        m.put("failedStages", failedStages.get());
        m.put("skippedStages", skippedStages.get());
        m.put("reusedStages", reusedStages.get());
        m.put("successRate", round(successRate));
        m.put("totalRetries", totalRetries.get());
        m.put("totalRollbacks", totalRollbacks.get());
        m.put("approvalsRequested", approvalsRequested.get());
        m.put("approvalsRejected", approvalsRejected.get());
        m.put("mttrMillis", round(mttrMillis));
        m.put("endToEndLatencyMillis", endToEnd);
        return m;
    }

    public void printSummary() {
        Map<String, Object> s = snapshot();
        System.out.println();
        System.out.println("=== RUN METRICS ===");
        s.forEach((k, v) -> System.out.println("  " + k + " = " + v));
    }

    private static double round(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }
}

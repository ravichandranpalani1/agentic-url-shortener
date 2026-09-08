package com.schwab.orchestrator.governance;

import com.schwab.orchestrator.model.StageInput;

/**
 * Approves everything automatically. Exists only for local smoke-testing of
 * the graph/engine wiring; every decision is flagged {@code simulated=true}
 * so it can never be mistaken for a real approval in the audit log, and the
 * three shipped scenarios use {@link FileBackedApprovalGate} instead, which
 * requires an explicit recorded decision per gated stage.
 */
public final class AutoApproveGate implements ApprovalGate {

    @Override
    public ApprovalDecision requestApproval(String stageId, String reason, StageInput input) {
        return new ApprovalDecision(true, "auto", "AUTO-APPROVED (non-interactive smoke-test mode, not a real approval)",
                System.currentTimeMillis(), true);
    }
}

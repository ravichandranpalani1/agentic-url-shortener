package com.schwab.orchestrator.governance;

import com.schwab.orchestrator.model.StageInput;

/** Requests a human decision before a high-impact stage is allowed to proceed. */
public interface ApprovalGate {
    ApprovalDecision requestApproval(String stageId, String reason, StageInput input);
}

package com.schwab.orchestrator.governance;

public record ApprovalDecision(boolean approved, String approver, String rationale, long timestampMillis, boolean simulated) {
}

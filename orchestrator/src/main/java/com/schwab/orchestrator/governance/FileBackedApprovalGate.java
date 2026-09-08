package com.schwab.orchestrator.governance;

import com.schwab.common.json.Json;
import com.schwab.orchestrator.model.StageInput;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Reads approval decisions from a JSON file: {"stageId": {"approved": true,
 * "approver": "...", "rationale": "..."}}. This stands in for a real
 * approval-system-of-record (e.g. a ServiceNow change ticket or a Slack
 * approval workflow) in this prototype: it represents a human decision that
 * was made asynchronously, ahead of the run, and is now being consumed by
 * the orchestrator -- which is how many real CD pipelines consume a
 * pre-recorded change approval rather than blocking on a live prompt. A
 * stage with no matching entry fails closed (not approved), because an
 * approval gate that defaults to "yes" when it can't find a record is not
 * a control.
 */
public final class FileBackedApprovalGate implements ApprovalGate {

    private final Map<String, Object> decisions;

    public FileBackedApprovalGate(Path approvalsFile) {
        if (approvalsFile != null && Files.exists(approvalsFile)) {
            try {
                this.decisions = Json.parseObject(Files.readString(approvalsFile));
            } catch (IOException e) {
                throw new RuntimeException("Failed to read approvals file: " + approvalsFile, e);
            }
        } else {
            this.decisions = Map.of();
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public ApprovalDecision requestApproval(String stageId, String reason, StageInput input) {
        Object raw = decisions.get(stageId);
        if (!(raw instanceof Map)) {
            return new ApprovalDecision(false, "none", "No recorded approval found for stage '" + stageId
                    + "' (failing closed)", System.currentTimeMillis(), false);
        }
        Map<String, Object> entry = (Map<String, Object>) raw;
        boolean approved = Boolean.TRUE.equals(entry.get("approved"));
        String approver = String.valueOf(entry.getOrDefault("approver", "unknown"));
        String rationale = String.valueOf(entry.getOrDefault("rationale", ""));
        return new ApprovalDecision(approved, approver, rationale, System.currentTimeMillis(), false);
    }
}

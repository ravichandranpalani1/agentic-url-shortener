package com.schwab.orchestrator.governance;

import com.schwab.orchestrator.model.StageInput;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Blocks on stdin and asks a real person at the keyboard to approve or reject. */
public final class ConsoleApprovalGate implements ApprovalGate {

    @Override
    public ApprovalDecision requestApproval(String stageId, String reason, StageInput input) {
        System.out.println();
        System.out.println("=== HUMAN APPROVAL REQUIRED ===");
        System.out.println("Stage:  " + stageId);
        System.out.println("Reason: " + reason);
        System.out.print("Approve? [y/N] and optional rationale (e.g. \"y looks good\"): ");
        System.out.flush();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line = reader.readLine();
            if (line == null) {
                line = "";
            }
            line = line.trim();
            boolean approved = line.toLowerCase().startsWith("y");
            String rationale = line.length() > 1 ? line.substring(1).trim() : "";
            String approver = System.getProperty("user.name", "operator");
            return new ApprovalDecision(approved, approver, rationale, System.currentTimeMillis(), false);
        } catch (IOException e) {
            return new ApprovalDecision(false, "none", "Failed to read approval from stdin: " + e.getMessage(),
                    System.currentTimeMillis(), false);
        }
    }
}

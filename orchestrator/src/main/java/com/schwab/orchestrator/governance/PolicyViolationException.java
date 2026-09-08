package com.schwab.orchestrator.governance;

/**
 * Raised by a {@link PolicyGuardrail} when a stage's inputs or outputs
 * violate policy. {@code safeStop} distinguishes a stage-local failure
 * (this stage fails, its dependents are skipped, the rest of the graph
 * proceeds) from a critical violation that must halt the entire run
 * immediately (e.g. a suspected secret leak).
 */
public final class PolicyViolationException extends Exception {
    public final String guardrailName;
    public final boolean safeStop;

    public PolicyViolationException(String guardrailName, String message, boolean safeStop) {
        super(message);
        this.guardrailName = guardrailName;
        this.safeStop = safeStop;
    }
}

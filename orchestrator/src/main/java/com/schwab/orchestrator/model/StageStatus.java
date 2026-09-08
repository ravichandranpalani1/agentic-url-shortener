package com.schwab.orchestrator.model;

public enum StageStatus {
    PENDING,
    REUSED,          // input unchanged since last run; previous output carried forward (dynamic re-planning)
    STALE,           // input changed since last run; must re-execute
    RUNNING,
    BLOCKED_ON_APPROVAL,
    RETRYING,
    ROLLED_BACK,
    SKIPPED,         // an upstream dependency failed, was rejected, or safe-stop tripped before this stage started
    FAILED,
    COMPLETED
}

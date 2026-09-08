package com.schwab.orchestrator.graph;

import com.schwab.orchestrator.agents.Agent;
import com.schwab.orchestrator.governance.PolicyGuardrail;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Immutable definition of one node in the SDLC dependency graph. Build with {@link #builder}. */
public final class Stage {
    private final String id;
    private final String name;
    private final Set<String> dependsOn;
    private final Agent agent;
    private final boolean requiresApproval;
    private final String approvalReason;
    private final int maxRetries;
    private final long retryBackoffMillis;
    private final Agent fallbackAgent;
    private final Agent rollbackAgent;
    private final List<PolicyGuardrail> guardrails;
    private final Map<String, Object> params;

    private Stage(Builder b) {
        this.id = b.id;
        this.name = b.name;
        this.dependsOn = Set.copyOf(b.dependsOn);
        this.agent = b.agent;
        this.requiresApproval = b.requiresApproval;
        this.approvalReason = b.approvalReason;
        this.maxRetries = b.maxRetries;
        this.retryBackoffMillis = b.retryBackoffMillis;
        this.fallbackAgent = b.fallbackAgent;
        this.rollbackAgent = b.rollbackAgent;
        this.guardrails = List.copyOf(b.guardrails);
        this.params = Map.copyOf(b.params);
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public Set<String> dependsOn() {
        return dependsOn;
    }

    public Agent agent() {
        return agent;
    }

    public boolean requiresApproval() {
        return requiresApproval;
    }

    public String approvalReason() {
        return approvalReason;
    }

    public int maxRetries() {
        return maxRetries;
    }

    public long retryBackoffMillis() {
        return retryBackoffMillis;
    }

    public Agent fallbackAgent() {
        return fallbackAgent;
    }

    public Agent rollbackAgent() {
        return rollbackAgent;
    }

    public List<PolicyGuardrail> guardrails() {
        return guardrails;
    }

    public Map<String, Object> params() {
        return params;
    }

    public static Builder builder(String id, String name, Agent agent) {
        return new Builder(id, name, agent);
    }

    public static final class Builder {
        private final String id;
        private final String name;
        private final Agent agent;
        private final Set<String> dependsOn = new LinkedHashSet<>();
        private boolean requiresApproval = false;
        private String approvalReason = "";
        private int maxRetries = 1;
        private long retryBackoffMillis = 100;
        private Agent fallbackAgent;
        private Agent rollbackAgent;
        private final List<PolicyGuardrail> guardrails = new ArrayList<>();
        private final Map<String, Object> params = new LinkedHashMap<>();

        private Builder(String id, String name, Agent agent) {
            this.id = id;
            this.name = name;
            this.agent = agent;
        }

        public Builder dependsOn(String... ids) {
            dependsOn.addAll(List.of(ids));
            return this;
        }

        public Builder requiresApproval(String reason) {
            this.requiresApproval = true;
            this.approvalReason = reason;
            return this;
        }

        public Builder maxRetries(int n) {
            this.maxRetries = n;
            return this;
        }

        public Builder retryBackoffMillis(long ms) {
            this.retryBackoffMillis = ms;
            return this;
        }

        public Builder fallback(Agent agent) {
            this.fallbackAgent = agent;
            return this;
        }

        public Builder rollback(Agent agent) {
            this.rollbackAgent = agent;
            return this;
        }

        public Builder guardrail(PolicyGuardrail guardrail) {
            this.guardrails.add(guardrail);
            return this;
        }

        public Builder param(String key, Object value) {
            this.params.put(key, value);
            return this;
        }

        public Stage build() {
            return new Stage(this);
        }
    }
}

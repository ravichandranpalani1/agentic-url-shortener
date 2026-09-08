package com.schwab.orchestrator.governance;

import com.schwab.orchestrator.model.StageInput;

import java.util.List;
import java.util.Set;

/**
 * Change-control guardrail: certain paths (the orchestration engine's own
 * source, git internals, the service's durability log) must never be
 * modified by an implementation agent, regardless of approvals -- approving
 * a feature change is not the same as authorizing the agent to rewrite its
 * own control plane. Stages that intend to touch files declare them in
 * {@code params["targetFiles"]}; this guardrail runs before execution so a
 * violation is caught before any write happens.
 */
public final class RestrictedPathGuardrail implements PolicyGuardrail {

    private static final Set<String> RESTRICTED_PREFIXES = Set.of(
            "orchestrator/", ".git/", "service/src/main/java/com/schwab/urlshortener/UrlShortenerApplication.java",
            "service/pom.xml");

    @Override
    public String name() {
        return "restricted-path";
    }

    @Override
    @SuppressWarnings("unchecked")
    public void checkBefore(String stageId, StageInput input) throws PolicyViolationException {
        Object raw = input.params().get("targetFiles");
        if (!(raw instanceof List<?> list)) {
            return;
        }
        for (Object o : list) {
            String target = String.valueOf(o);
            for (String restricted : RESTRICTED_PREFIXES) {
                if (target.startsWith(restricted)) {
                    throw new PolicyViolationException(name(),
                            "Stage " + stageId + " declared a target file under restricted path '" + restricted
                                    + "': " + target,
                            false);
                }
            }
        }
    }
}

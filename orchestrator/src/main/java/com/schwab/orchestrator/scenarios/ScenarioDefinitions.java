package com.schwab.orchestrator.scenarios;

import com.schwab.orchestrator.agents.ClarificationAgent;
import com.schwab.orchestrator.agents.DesignAgent;
import com.schwab.orchestrator.agents.DocsAgent;
import com.schwab.orchestrator.agents.ImplementationAgent;
import com.schwab.orchestrator.agents.NotifyDownstreamAgent;
import com.schwab.orchestrator.agents.QueueForManualFollowUpAgent;
import com.schwab.orchestrator.agents.ReleaseReadinessAgent;
import com.schwab.orchestrator.agents.RequirementsAgent;
import com.schwab.orchestrator.agents.RevertFileAgent;
import com.schwab.orchestrator.agents.TestingAgent;
import com.schwab.orchestrator.governance.RestrictedPathGuardrail;
import com.schwab.orchestrator.governance.SecretScanGuardrail;
import com.schwab.orchestrator.graph.Stage;
import com.schwab.orchestrator.graph.WorkflowGraph;

import java.util.List;
import java.util.Map;

/**
 * Builds the three required scenario graphs (greenfield, brownfield,
 * ambiguous). Each is a real, distinct SDLC workflow over the actual
 * service codebase -- see docs/scenarios/ for the narrative behind each
 * one's design choices.
 */
public final class ScenarioDefinitions {

    private ScenarioDefinitions() {
    }

    public static WorkflowGraph forName(String name) {
        return switch (name) {
            case "greenfield" -> greenfield();
            case "brownfield" -> brownfield();
            case "ambiguous" -> ambiguous();
            default -> throw new IllegalArgumentException("Unknown scenario: " + name
                    + " (expected greenfield, brownfield or ambiguous)");
        };
    }

    /** Greenfield: build the service from scratch. Headlines the retry-recovers-a-transient-failure path. */
    public static WorkflowGraph greenfield() {
        String requirement = "Build a URL shortener service from scratch with a create API, a redirect API, "
                + "per-code click analytics, custom aliases, link expiration, rate limiting, and a "
                + "health/metrics endpoint. It must handle at least 3 concurrent redirects without errors "
                + "and must recover its state after a restart.";

        Stage requirements = Stage.builder("requirements", "Interpret requirement", new RequirementsAgent())
                .param("requirementText", requirement)
                .build();

        Stage design = Stage.builder("design", "Design / impact scan", new DesignAgent())
                .dependsOn("requirements")
                .build();

        Stage implementation = Stage.builder("implementation", "Verify greenfield scaffold", new ImplementationAgent())
                .dependsOn("design")
                .param("mode", "verify-scaffold")
                .param("expectedFiles", List.of(
                        "service/src/main/java/com/schwab/urlshortener/UrlShortenerServer.java",
                        "service/src/main/java/com/schwab/urlshortener/Bootstrap.java",
                        "service/src/main/java/com/schwab/urlshortener/store/InMemoryUrlStore.java",
                        "service/src/main/java/com/schwab/urlshortener/store/WriteAheadLog.java",
                        "service/src/main/java/com/schwab/urlshortener/http/RedirectHandler.java",
                        "service/src/main/java/com/schwab/urlshortener/http/UrlsCollectionHandler.java",
                        "service/src/main/java/com/schwab/urlshortener/http/UrlItemHandler.java",
                        "service/src/main/java/com/schwab/urlshortener/rate/RateLimiter.java",
                        "service/src/main/java/com/schwab/urlshortener/util/Base62Encoder.java",
                        "service/src/main/java/com/schwab/urlshortener/validation/UrlValidator.java"))
                // Deterministically exercises the bounded-retry path: fails on attempt 1, succeeds on attempt 2.
                .param("simulateTransientFailureUntilAttempt", 2)
                .maxRetries(3)
                .retryBackoffMillis(50)
                .requiresApproval("Marking the full greenfield service surface area as built requires sign-off")
                .guardrail(new RestrictedPathGuardrail())
                .guardrail(new SecretScanGuardrail())
                .build();

        Stage testing = Stage.builder("testing", "Run real test suite", new TestingAgent())
                .dependsOn("implementation")
                .param("scope", "service")
                .maxRetries(2)
                .retryBackoffMillis(200)
                .build();

        Stage docs = Stage.builder("docs", "Generate run report", new DocsAgent())
                .dependsOn("testing")
                .build();

        Stage notify = Stage.builder("notify", "Notify downstream channel", new NotifyDownstreamAgent())
                .dependsOn("testing")
                .param("alwaysFail", false)
                .fallback(new QueueForManualFollowUpAgent())
                .build();

        Stage release = Stage.builder("release", "Release readiness", new ReleaseReadinessAgent())
                .dependsOn("docs", "notify")
                .build();

        return new WorkflowGraph(List.of(requirements, design, implementation, testing, docs, notify, release));
    }

    /** Brownfield: add GET /api/v1/urls/expired to the existing service. Headlines the fallback-after-retries path. */
    public static WorkflowGraph brownfield() {
        String requirement = "Add a way to list short URLs that are past their expiration but have not been "
                + "cleaned up yet, so an operator can find them without scanning every record by hand.";

        Stage requirements = Stage.builder("requirements", "Interpret requirement", new RequirementsAgent())
                .param("requirementText", requirement)
                .build();

        Stage design = Stage.builder("design", "Design / impact scan", new DesignAgent())
                .dependsOn("requirements")
                .build();

        List<Map<String, Object>> changes = List.of(
                Map.of("targetFile", "service/src/main/java/com/schwab/urlshortener/http/UrlItemHandler.java",
                        "sourceAssetPath", "scenario-assets/brownfield/UrlItemHandler.java"),
                Map.of("targetFile", "service/src/main/java/com/schwab/urlshortener/validation/AliasValidator.java",
                        "sourceAssetPath", "scenario-assets/brownfield/AliasValidator.java"),
                Map.of("targetFile", "service/src/test/java/com/schwab/urlshortener/ExpiredUrlsFeatureTest.java",
                        "sourceAssetPath", "scenario-assets/brownfield/ExpiredUrlsFeatureTest.java"));

        Stage implementation = Stage.builder("implementation", "Apply brownfield change", new ImplementationAgent())
                .dependsOn("design")
                .param("mode", "apply-change")
                .param("changes", changes)
                .param("targetFiles", List.of(
                        "service/src/main/java/com/schwab/urlshortener/http/UrlItemHandler.java",
                        "service/src/main/java/com/schwab/urlshortener/validation/AliasValidator.java",
                        "service/src/test/java/com/schwab/urlshortener/ExpiredUrlsFeatureTest.java"))
                .requiresApproval("Modifies request routing logic in an existing, tested handler; requires sign-off")
                .guardrail(new RestrictedPathGuardrail())
                .guardrail(new SecretScanGuardrail())
                .build();

        Stage testing = Stage.builder("testing", "Run real test suite", new TestingAgent())
                .dependsOn("implementation")
                .param("scope", "service")
                .maxRetries(2)
                .retryBackoffMillis(200)
                .build();

        Stage docs = Stage.builder("docs", "Generate run report", new DocsAgent())
                .dependsOn("testing")
                .build();

        // Deliberately always fails (simulated downstream outage) so retries are exhausted for real
        // and the fallback path genuinely runs -- see NotifyDownstreamAgent's javadoc.
        Stage notify = Stage.builder("notify", "Notify downstream channel", new NotifyDownstreamAgent())
                .dependsOn("testing")
                .param("alwaysFail", true)
                .maxRetries(2)
                .retryBackoffMillis(50)
                .fallback(new QueueForManualFollowUpAgent())
                .build();

        Stage release = Stage.builder("release", "Release readiness", new ReleaseReadinessAgent())
                .dependsOn("docs", "notify")
                .build();

        return new WorkflowGraph(List.of(requirements, design, implementation, testing, docs, notify, release));
    }

    /** Ambiguous: "make analytics more reliable". Headlines requirement clarification + a real rollback. */
    public static WorkflowGraph ambiguous() {
        String requirement = "Make the click analytics more reliable.";

        String chosen = "Defend the analytics executor submission against RejectedExecutionException so a "
                + "saturated/shutting-down executor degrades to a logged drop instead of throwing on the "
                + "request thread, while keeping analytics fully asynchronous.";
        String rejected = "Record analytics synchronously before responding, guaranteeing no click is ever "
                + "lost at the cost of coupling redirect latency to the analytics write path.";

        Stage requirements = Stage.builder("requirements", "Interpret requirement", new RequirementsAgent())
                .param("requirementText", requirement)
                .build();

        Stage clarify = Stage.builder("clarify-requirement", "Clarify ambiguous requirement", new ClarificationAgent())
                .dependsOn("requirements")
                .param("chosenInterpretation", chosen)
                .param("rejectedInterpretation", rejected)
                .requiresApproval("Requirement is ambiguous (\"more reliable\" has no measurable acceptance "
                        + "criteria); a human must pick a concrete interpretation before design/implementation proceed")
                .build();

        Stage design = Stage.builder("design", "Design / impact scan", new DesignAgent())
                .dependsOn("clarify-requirement")
                .build();

        List<Map<String, Object>> realChange = List.of(
                Map.of("targetFile", "service/src/main/java/com/schwab/urlshortener/http/RedirectHandler.java",
                        "sourceAssetPath", "scenario-assets/ambiguous/RedirectHandler.retry-approved.java"));

        Stage implementation = Stage.builder("implementation", "Apply clarified change", new ImplementationAgent())
                .dependsOn("design")
                .param("mode", "apply-change")
                .param("changes", realChange)
                .param("targetFiles", List.of("service/src/main/java/com/schwab/urlshortener/http/RedirectHandler.java"))
                .requiresApproval("Changes the redirect path's analytics-submission handling; requires sign-off")
                .guardrail(new RestrictedPathGuardrail())
                .guardrail(new SecretScanGuardrail())
                .build();

        // Isolated governance drill: proves the rollback mechanism against a harmless scratch file,
        // independent of the real feature change above -- see docs/scenarios/03-ambiguous.md. Nothing
        // depends on this stage, so its (deterministic, expected) failure+rollback never blocks release.
        List<Map<String, Object>> drillChange = List.of(
                Map.of("targetFile", "docs/scenarios/_rollback_drill_scratch.md",
                        "sourceAssetPath", "scenario-assets/ambiguous/rollback-drill-content.txt"));

        Stage rollbackDrill = Stage.builder("rollback-drill", "Rollback mechanism drill", new ImplementationAgent())
                .dependsOn("design")
                .param("mode", "apply-change-with-rollback-demo")
                .param("changes", drillChange)
                .param("targetFiles", List.of("docs/scenarios/_rollback_drill_scratch.md"))
                .param("selfCheckShouldFail", true)
                .maxRetries(1)
                .rollback(new RevertFileAgent())
                .build();

        Stage testing = Stage.builder("testing", "Run real test suite", new TestingAgent())
                .dependsOn("implementation")
                .param("scope", "service")
                .maxRetries(2)
                .retryBackoffMillis(200)
                .build();

        Stage docs = Stage.builder("docs", "Generate run report", new DocsAgent())
                .dependsOn("testing")
                .build();

        Stage notify = Stage.builder("notify", "Notify downstream channel", new NotifyDownstreamAgent())
                .dependsOn("testing")
                .param("alwaysFail", false)
                .fallback(new QueueForManualFollowUpAgent())
                .build();

        Stage release = Stage.builder("release", "Release readiness", new ReleaseReadinessAgent())
                .dependsOn("docs", "notify")
                .build();

        return new WorkflowGraph(List.of(requirements, clarify, design, implementation, rollbackDrill,
                testing, docs, notify, release));
    }
}

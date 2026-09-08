package com.schwab.orchestrator.governance;

import com.schwab.orchestrator.model.StageInput;
import com.schwab.orchestrator.model.StageOutput;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * Security guardrail: scans every artifact a stage claims to have written
 * for patterns that look like a leaked credential. This runs after every
 * stage that produces file artifacts (design notes, code changes, docs) --
 * a real defense against an agent (or a human) accidentally committing a
 * secret, not just a naming convention.
 *
 * A match trips {@code safeStop = true}: a suspected secret in a
 * to-be-committed artifact halts the entire orchestrator run rather than
 * just failing one stage, because downstream stages (docs, release) must
 * not proceed as if nothing happened.
 */
public final class SecretScanGuardrail implements PolicyGuardrail {

    private static final Pattern[] SECRET_PATTERNS = {
            Pattern.compile("AKIA[0-9A-Z]{16}"), // AWS access key id
            Pattern.compile("(?i)(api[_-]?key|secret|password|passwd|token)\\s*[:=]\\s*['\"][A-Za-z0-9/+_.=-]{10,}['\"]"),
            Pattern.compile("-----BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY-----"),
    };

    @Override
    public String name() {
        return "secret-scan";
    }

    @Override
    public void checkAfter(String stageId, StageInput input, StageOutput output) throws PolicyViolationException {
        Path repoRoot = input.context().repoRoot();
        for (String relativePath : output.artifacts()) {
            Path path = repoRoot.resolve(relativePath);
            if (!Files.isRegularFile(path)) {
                continue;
            }
            String content;
            try {
                content = Files.readString(path);
            } catch (IOException e) {
                continue; // unreadable (e.g. binary) -- not this guardrail's concern
            }
            for (Pattern pattern : SECRET_PATTERNS) {
                if (pattern.matcher(content).find()) {
                    throw new PolicyViolationException(name(),
                            "Artifact " + relativePath + " produced by stage " + stageId
                                    + " matches a credential-like pattern (" + pattern.pattern() + ")",
                            true);
                }
            }
        }
    }
}

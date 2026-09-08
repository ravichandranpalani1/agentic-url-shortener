package com.schwab.orchestrator.agents;

import com.schwab.orchestrator.model.StageInput;
import com.schwab.orchestrator.model.StageOutput;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Rollback agent paired with {@link ImplementationAgent}: for each file in
 * the failed stage's {@code changes} list, restores the pre-change backup
 * {@link ImplementationAgent} snapshotted before overwriting it, or --
 * if there was no backup, meaning the file did not exist before this stage
 * ran -- deletes it. Restoring from a real backup (rather than always
 * deleting) is what makes this safe to use against a change that
 * overwrites an existing source file, not only one that creates a new
 * file.
 */
public final class RevertFileAgent implements Agent {

    @Override
    @SuppressWarnings("unchecked")
    public StageOutput execute(StageInput input) throws Exception {
        List<Map<String, Object>> changes = (List<Map<String, Object>>) (List<?>) input.params().getOrDefault("changes", List.of());
        Path repoRoot = input.context().repoRoot();
        Path backupDir = input.context().runDir().resolve("backups");

        List<String> restored = new ArrayList<>();
        List<String> deleted = new ArrayList<>();
        for (Map<String, Object> change : changes) {
            String targetFile = String.valueOf(change.get("targetFile"));
            Path target = repoRoot.resolve(targetFile);
            Path backup = backupDir.resolve(targetFile.replace('/', '_') + ".bak");

            if (Files.exists(backup)) {
                Files.copy(backup, target, StandardCopyOption.REPLACE_EXISTING);
                restored.add(targetFile);
            } else if (Files.deleteIfExists(target)) {
                deleted.add(targetFile);
            }
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("restoredFiles", restored);
        data.put("deletedFiles", deleted);
        return new StageOutput(data, List.of(),
                "Rollback complete: restored " + restored.size() + " pre-existing file(s), deleted "
                        + deleted.size() + " newly-created file(s)");
    }
}

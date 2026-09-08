package com.schwab.orchestrator.audit;

import com.schwab.common.json.Json;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Append-only, audit-grade event log for one orchestrator run: every gate,
 * retry, approval, rollback and completion is written as one JSON line with
 * a shared trace id, so the full decision lineage of a run can be
 * reconstructed after the fact without relying on stdout.
 */
public final class AuditLogger {

    private final Path logFile;
    private final String traceId;

    public AuditLogger(Path logFile, String traceId) {
        this.logFile = logFile;
        this.traceId = traceId;
        try {
            if (logFile.getParent() != null) {
                Files.createDirectories(logFile.getParent());
            }
            Files.deleteIfExists(logFile);
            Files.createFile(logFile);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to initialize audit log at " + logFile, e);
        }
    }

    public String traceId() {
        return traceId;
    }

    public synchronized void log(String stageId, String eventType, int attempt, Map<String, Object> details) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("traceId", traceId);
        event.put("timestamp", System.currentTimeMillis());
        event.put("stageId", stageId);
        event.put("eventType", eventType);
        event.put("attempt", attempt);
        event.put("details", details == null ? Map.of() : details);
        String line = Json.write(event) + "\n";
        try {
            Files.writeString(logFile, line, StandardCharsets.UTF_8, StandardOpenOption.APPEND, StandardOpenOption.CREATE);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to append to audit log", e);
        }
        System.out.println("[" + eventType + "] " + stageId + (attempt > 1 ? " (attempt " + attempt + ")" : "")
                + (details != null && details.containsKey("message") ? " -- " + details.get("message") : ""));
    }
}

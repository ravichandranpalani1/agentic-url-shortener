package com.schwab.urlshortener.store;

import com.schwab.common.json.Json;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A minimal append-only write-ahead log: every mutation is appended as one
 * JSON line before it is applied to in-memory state, so a crashed process
 * can rebuild its state by replaying the file on the next startup. This is
 * the store's reliability/durability mechanism in place of an external
 * database (see docs/architecture.md).
 */
public final class WriteAheadLog {

    private final Path logFile;

    public WriteAheadLog(Path logFile) {
        this.logFile = logFile;
        try {
            if (logFile.getParent() != null) {
                Files.createDirectories(logFile.getParent());
            }
            if (!Files.exists(logFile)) {
                Files.createFile(logFile);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to initialize write-ahead log at " + logFile, e);
        }
    }

    public synchronized void append(Map<String, Object> event) {
        String line = Json.write(event) + "\n";
        try {
            Files.writeString(logFile, line, StandardCharsets.UTF_8,
                    StandardOpenOption.APPEND, StandardOpenOption.CREATE);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to append to write-ahead log", e);
        }
    }

    /** Replays every event in the log, in order. Returns an empty list for a fresh log. */
    public List<Map<String, Object>> replay() {
        List<Map<String, Object>> events = new ArrayList<>();
        try {
            List<String> lines = Files.readAllLines(logFile, StandardCharsets.UTF_8);
            for (String line : lines) {
                if (line.isBlank()) {
                    continue;
                }
                events.add(Json.parseObject(line));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to replay write-ahead log", e);
        }
        return events;
    }
}

package com.testtask.outlookagent.audit;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * Append-only, restart-safe audit journal backed by a plain UTF-8 text file.
 * Each line is one SHA-256 hash-chained entry; new entries are only ever
 * appended, existing bytes are never rewritten.
 */
public class FileAuditJournal implements AuditJournal {

    private static final String FIELD_DELIMITER = "\\|";
    private static final String GENESIS_HASH = genesisHash();

    private final Path path;

    public FileAuditJournal(Path path) {
        this.path = path;
    }

    @Override
    public synchronized void append(AuditEvent event) {
        List<AuditEntry> existing = parseEntries();
        String previousHash = existing.isEmpty() ? GENESIS_HASH : existing.get(existing.size() - 1).getHash();
        String hash = computeHash(previousHash, event);
        String line = serialize(event, previousHash, hash);
        try {
            if (!Files.exists(path)) {
                Path parent = path.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.createFile(path);
            }
            Files.write(path, (line + System.lineSeparator()).getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to append audit entry", e);
        }
    }

    @Override
    public List<AuditEntry> readAll() {
        return parseEntries();
    }

    @Override
    public boolean verifyChainIntegrity() {
        List<AuditEntry> entries;
        try {
            entries = parseEntries();
        } catch (RuntimeException e) {
            return false;
        }

        String expectedPrevious = GENESIS_HASH;
        for (AuditEntry entry : entries) {
            if (!expectedPrevious.equals(entry.getPreviousHash())) {
                return false;
            }
            String recomputed = computeHash(entry.getPreviousHash(), entry.getEvent());
            if (!recomputed.equals(entry.getHash())) {
                return false;
            }
            expectedPrevious = entry.getHash();
        }
        return true;
    }

    private List<AuditEntry> parseEntries() {
        List<AuditEntry> entries = new ArrayList<>();
        if (!Files.exists(path)) {
            return entries;
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read audit journal", e);
        }
        for (String line : lines) {
            if (line.isEmpty()) {
                continue;
            }
            entries.add(parseLine(line));
        }
        return entries;
    }

    private AuditEntry parseLine(String line) {
        String[] parts = line.split(FIELD_DELIMITER, -1);
        if (parts.length != 6) {
            throw new IllegalStateException("Malformed audit journal line");
        }
        String eventKey = parts[0];
        String hashedMessageRef = emptyToNull(parts[1]);
        String toolName = emptyToNull(parts[2]);
        long timestamp = Long.parseLong(parts[3]);
        String previousHash = parts[4];
        String hash = parts[5];
        AuditEvent event = new AuditEvent(eventKey, hashedMessageRef, toolName, timestamp);
        return new AuditEntry(event, previousHash, hash);
    }

    private String serialize(AuditEvent event, String previousHash, String hash) {
        return String.join("|",
                nullToEmpty(event.getEventKey()),
                nullToEmpty(event.getHashedMessageRef()),
                nullToEmpty(event.getToolName()),
                String.valueOf(event.getTimestamp()),
                previousHash,
                hash);
    }

    private String computeHash(String previousHash, AuditEvent event) {
        String payload = previousHash
                + nullToEmpty(event.getEventKey())
                + nullToEmpty(event.getHashedMessageRef())
                + nullToEmpty(event.getToolName())
                + event.getTimestamp();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return toHex(digest.digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String emptyToNull(String value) {
        return value.isEmpty() ? null : value;
    }

    private static String genesisHash() {
        StringBuilder sb = new StringBuilder(64);
        for (int i = 0; i < 64; i++) {
            sb.append('0');
        }
        return sb.toString();
    }
}
